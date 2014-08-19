package de.kopis.withings;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

public class CallbackServlet extends HttpServlet {

    private Map<String, String[]> params = null;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // only accept the first valid callback
        if(!req.getParameterMap().containsKey("print") && params == null) {
            // store callback parameters, application will retrieve them later
            params = req.getParameterMap();
        } else {
            if(params == null) {
                resp.sendError(500, "No data available yet");
            } else {
                // write out previously received parameters
                for (String param : params.keySet()) {
                    resp.getWriter().write(param + "=" + params.get(param)[0] + "\n");
                }
            }
        }
    }
}