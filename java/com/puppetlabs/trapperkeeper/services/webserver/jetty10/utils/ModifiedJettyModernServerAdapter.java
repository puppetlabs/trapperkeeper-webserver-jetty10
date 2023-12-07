package com.puppetlabs.trapperkeeper.services.webserver.jetty10.utils;

import ch.qos.logback.access.jetty.JettyModernServerAdapter;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

import java.util.HashMap;
import java.util.Map;

public class ModifiedJettyModernServerAdapter extends JettyModernServerAdapter {
    // these are package private in the JettyServerAdapter, unfortunately
    Request request;
    Response response;

    public ModifiedJettyModernServerAdapter(Request jettyRequest, Response jettyResponse) {
        super(jettyRequest, jettyResponse);
        this.response = jettyResponse;
        this.request = jettyRequest;
    }

    /**
     * buildResponseMap
     * This is a replacement of the buildResponseLog in JettyModernServerAdapter to
     * make it compatible with Jetty 10 responses. The provided version of logback-acccess
     * has a different signature expectation for "getHttpFields" which causes runtime failures.
     * @return a map of the headers.
     */
    @Override
    public Map<String, String> buildResponseHeaderMap() {
        Map<String, String> responseHeaderMap = new HashMap<String,String>();

        for (HttpField httpField : this.response.getHttpFields()) {
            String key = httpField.getName();
            String value = httpField.getValue();
            responseHeaderMap.put(key, value);
        }

        return responseHeaderMap;
    }
}
