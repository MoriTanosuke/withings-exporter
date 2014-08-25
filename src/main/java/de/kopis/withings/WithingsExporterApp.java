package de.kopis.withings;

import com.google.gson.Gson;
import de.kopis.withings.api.WithingsApi;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.*;
import org.scribe.oauth.OAuthService;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class WithingsExporterApp {

    public static final String WITHINGS_API_BASEURL = "http://wbsapi.withings.net/v2";
    /**
     * API key to access Withings data.
     */
    private final String YOUR_API_KEY;
    /**
     * API secret to access Withings data.
     */
    private final String YOUR_API_SECRET;
    /**
     * Enable/disable debug logging.
     */
    private final boolean debug;
    /**
     * Local jetty webserver to receive OAuth authorization callbacks.
     */
    private Server server;

    public WithingsExporterApp(String apiKey, String apiSecret, boolean debug) {
        YOUR_API_KEY = apiKey;
        YOUR_API_SECRET = apiSecret;
        this.debug = debug;
    }

    /**
     * Starts an export of your Withings data. Parameters to this application are your API key and your API secret.
     *
     * @param args API key, API secret
     */
    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        //TODO make key+secret required arguments
        parser.accepts("key", "you Withings API key").withRequiredArg().ofType(String.class).required();
        parser.accepts("secret", "the secret to your Withings API key").requiredIf("key").withRequiredArg().ofType(String.class).required();
        parser.accepts("out", "filename to write output to").withRequiredArg().ofType(String.class);
        parser.accepts("range", "timerange to export, i.e. 30, 60, 90").withRequiredArg().ofType(Integer.class).defaultsTo(30);
        parser.accepts("debug", "enable debug logging");
        OptionSet options = null;
        try {
            options = parser.parse(args);
        } catch (Exception e) {
            System.err.println("Can not read options. Please check the provided options on your command line.");
            parser.printHelpOn(System.out);
            System.exit(-1);
        }

        final String key = (String) options.valueOf("key");
        final String secret = (String) options.valueOf("secret");
        final Integer range = (Integer) options.valueOf("range");
        final boolean debug = options.has("debug");

        OutputStream out = null;
        try {
            if (options.has("out") && options.hasArgument("out")) {
                final String filename = (String) options.valueOf("out");
                out = new FileOutputStream(filename);
            } else {
                out = System.out;
            }

            new WithingsExporterApp(key, secret, debug).export(out, range);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            //TODO write to crashlog
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Starts an export of your Withings data via the API.
     */
    private void export(OutputStream out, Integer timerange) throws IOException {
        OAuthService service = null;
        Token accessToken = null;
        Properties props = new Properties();

        //TODO make this more random
        int jettyPort = 8080;
        // need a local webserver to get callbacks from Withings
        try {
            startJetty(jettyPort);

            ServiceBuilder serviceBuilder = new ServiceBuilder()
                    .provider(WithingsApi.class)
                    .apiKey(YOUR_API_KEY)
                    .apiSecret(YOUR_API_SECRET)
                    .signatureType(SignatureType.QueryString)
                    .callback("http://localhost:" + jettyPort + "/callback");
            if (debug) {
                serviceBuilder.debug().debugStream(new FileOutputStream("debug.log"));
            }
            service = serviceBuilder.build();

            final Token requestToken = service.getRequestToken();
            final String authUrl = service.getAuthorizationUrl(requestToken);

            System.out.println("Open the following URL in your browser:");
            System.out.println(authUrl);

            //TODO get the data from the CallbackServlet
            boolean calledBack = false;
            while (!calledBack) {
                // get data from the CallbackServlet
                HttpClient httpClient = new HttpClient();
                httpClient.start();
                ContentResponse response = httpClient.GET("http://localhost:" + jettyPort + "/callback?print=true");
                if (response.getStatus() != 200) {
                    // if no data available yet, sleep for 500ms
                    System.out.print(".");
                    Thread.sleep(500);
                } else {
                    // data is available now, get it and save it as properties for later use
                    System.out.println();
                    final String data = response.getContentAsString();
                    httpClient.stop();
                    props.load(new StringReader(data));
                    calledBack = true;
                }
            }
            System.out.println("Callback received, continuing.");

            final String verify = props.getProperty("oauth_verifier");
            Verifier verifier = new Verifier(verify);
            accessToken = service.getAccessToken(requestToken, verifier);

            // no more callbacks needed, stop local webserver
            stopJetty();
        } catch (Exception e) {
            System.err.println("Can not authorize with Withings. Please make sure everything is set up correctly.");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(-100);
        }

        final String userid = props.getProperty("userid");

        final String startdate = getDateFromRange(-1 * timerange);
        final String today = getDateFromRange(0);

        // query activities in the given range
        final Map response = queryApi(service, accessToken, "/measure", "getactivity", userid, startdate, today);

        // convert from JSON into a simple Map for easy parsing
        // print out a temporary CSV
        BufferedWriter fw = new BufferedWriter(new OutputStreamWriter(out));
        fw.append("Date,Steps,Calories,Elevation\n");
        for (Map body : (List<Map>) response.get("activities")) {
            fw.append(body.get("date") + "," + body.get("steps") + "," + body.get("calories") + "," + body.get("elevation") + "\n");
        }
        fw.flush();

        // check if jetty is still running
        if (server != null && server.isRunning()) {
            System.out.println("Trying to shut down local webserver. Use CTRL+C if nothing happens...");
            try {
                server.join();
                server.stop();
            } catch (Exception e) {
                // ignore this, we're going to shut down anyway
            }
        }
    }

    /**
     * Send a request to the Withings API and return the response as a {@link String}.
     *
     * @param service     OAuth service to use
     * @param accessToken a valid OAuth access token
     * @param method      API method to call
     * @param action      API action parameter to use when calling the API method
     * @param userid      user ID to use for the request
     * @param startdate   start of the date range
     * @param enddate     end of the date range
     * @return
     */
    private Map queryApi(OAuthService service, Token accessToken, String method, String action, String userid, String startdate, String enddate) {
        OAuthRequest request = new OAuthRequest(Verb.GET, WITHINGS_API_BASEURL + method);
        // get activities first
        request.addQuerystringParameter("action", action);
        // always provide the userid with the request
        request.addQuerystringParameter("userid", userid);
        // add the date range to request
        request.addQuerystringParameter("startdateymd", startdate);
        request.addQuerystringParameter("enddateymd", enddate);
        // sign the request to make it valid
        service.signRequest(accessToken, request);
        // fetch response
        Response response = request.send();
        // return JSON object "body" only
        return (Map) new Gson().fromJson(response.getBody(), Map.class).get("body");
    }

    private String getDateFromRange(int range) {
        final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd");
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, range);
        return FORMAT.format(cal.getTime());
    }

    private void stopJetty() throws Exception {
        //TODO server.join(); ?
        server.stop();
    }

    private void startJetty(int port) throws Exception {
        server = new Server(port);
        server.setStopAtShutdown(true);

        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);

        // !! This is a raw Servlet, not a servlet that has been configured through a web.xml or anything like that !!
        handler.addServletWithMapping(CallbackServlet.class, "/callback");

        server.start();
    }
}
