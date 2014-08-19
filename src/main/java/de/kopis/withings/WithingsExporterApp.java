package de.kopis.withings;

import com.google.gson.Gson;
import de.kopis.withings.api.WithingsApi;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.*;
import org.scribe.oauth.OAuth10aServiceImpl;
import org.scribe.oauth.OAuthService;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

public class WithingsExporterApp {

    /**
     * API key to access Withings data.
     */
    private final String YOUR_API_KEY;
    /**
     * API secret to access Withings data.
     */
    private final String YOUR_API_SECRET;
    /**
     * Local jetty webserver to receive OAuth authorization callbacks.
     */
    private Server server;

    public WithingsExporterApp(String apiKey, String apiSecret) {
        YOUR_API_KEY = apiKey;
        YOUR_API_SECRET = apiSecret;
    }

    /**
     * Starts an export of your Withings data. Parameters to this application are your API key and your API secret.
     *
     * @param args API key, API secret
     */
    public static void main(String[] args) {
        if (args == null || args.length != 2) {
            System.err.println("Please provide API key and API secret as parameters.");
            System.out.println("USAGE: java " + WithingsExporterApp.class.getName() + " <API_KEY> <API_SECRET>");
            System.exit(-1);
        }
        new WithingsExporterApp(args[0], args[1]).export();
    }

    /**
     * Starts an export of your Withings data via the API.
     */
    private void export() {
        OAuthService service = null;
        Token accessToken = null;
        Properties props = new Properties();

        //TODO make this more random
        int jettyPort = 8080;
        // need a local webserver to get callbacks from Withings
        try {
            startJetty(jettyPort);

            // TODO how to avoid cast to class? need to use RequestTuner later!
            service = new ServiceBuilder()
                    .debug().debugStream(System.out)
                    .provider(WithingsApi.class)
                    .apiKey(YOUR_API_KEY)
                    .apiSecret(YOUR_API_SECRET)
                    .signatureType(SignatureType.QueryString)
                    .callback("http://localhost:" + jettyPort + "/callback")
                    .build();
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
                    final String data = response.getContentAsString();
                    httpClient.stop();
                    props.load(new StringReader(data));
                    calledBack = true;
                }
            }
            System.out.println("Callback received, continuing.");

            final String verify = props.getProperty("oauth_verifier");
            System.out.println("Verifier: " + verify);
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
        System.out.println("User ID: " + userid);

        final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd");

        OAuthRequest request = new OAuthRequest(Verb.GET, "http://wbsapi.withings.net/v2" + "/measure");
        request.addQuerystringParameter("action", "getactivity");
        //request.addQuerystringParameter("date", FORMAT.format(new Date()));
        //request.addQuerystringParameter("date", "2014-08-17");
        request.addQuerystringParameter("userid", userid);
        service.signRequest(accessToken, request);
        Response response = request.send(new RequestTuner() {
            @Override
            public void tune(Request request) {
                System.out.println("URL: " + request.getCompleteUrl());
            }
        });
        Map data = new Gson().fromJson(response.getBody(), Map.class);
        //System.out.println(data.get("body"));
        Map body = (Map) data.get("body");
        // print out a temporary CSV
        System.out.println("Steps,Calories,Elevation");
        System.out.println(body.get("steps") + "," + body.get("calories") + "," + body.get("elevation"));
    }

    private void stopJetty() throws Exception {
        server.stop();
    }

    private void startJetty(int port) throws Exception {
        server = new Server(port);

        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);

        // !! This is a raw Servlet, not a servlet that has been configured through a web.xml or anything like that !!
        handler.addServletWithMapping(CallbackServlet.class, "/callback");

        server.start();
    }
}
