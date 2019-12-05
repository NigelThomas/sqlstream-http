/*
// $Id: //depot/customers/rubicon/anodot/src/com/sqlstream/utility/AnodotPlugin/HttpPost.java#1 $
// Copyright (C) 2014-2019 SQLstream, Inc.
*/

package com.sqlstream.utility.http;

import java.io.*;
import java.sql.*;
import java.sql.Types;

import java.util.logging.Logger;
import java.util.Date;


import com.sqlstream.jdbc.StreamingPreparedStatement;
import com.sqlstream.jdbc.StreamingResultSet;
import com.sqlstream.jdbc.StreamingResultSet.RowEvent;
import com.sqlstream.jdbc.TimeoutException;
import com.sqlstream.plugin.impl.AbstractBaseUdx;

//import net.sf.farrago.jdbc.FarragoJdbcUtil;

import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.util.logging.Logger;
import java.util.logging.Level;

import java.net.URL;
import java.net.URLConnection;
import java.net.MalformedURLException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

import java.util.Base64;

/**
 * HttpPost UDX.
 *   Takes a JSON input stream and batches of records to the output (as an array)
 *   Sends a maximum of N rows in a batch (configurable)
 *   Sends a part batch after a timeout (configurable)
 */
public class HttpPost
{
    //~ Static fields/initializers --------------------------------------------

    //~ Instance fields -------------------------------------------------------

    /** Tracing / logging facility. */
    protected static final Logger tracer =
            Logger.getLogger("com.sqlstream.utility.JsonBatchArray");


    //~ Methods ---------------------------------------------------------------

    /**
     *
     * Batches incoming data rows into an array (currently only JSON supported)
     * Batch size of 0 => no batching and no mod to postData in the incoming row
     * Batch size > 0 => JSON array sent, and the batch array element is written out to the same column that included the incoming data
     * 
     * Adds an integer responseCode
     * adds response data (which may be JSON)
     *
     * CREATE OR REPLACE FUNCTION "HttpPost"
     *     ( inputRows            CURSOR         -- input stream
     *     , optionRows           CURSOR         -- options that can be set at run time
     *     , postDataColumnName   VARCHAR(128)
     *     )
     *    RETURNS STREAM
     *           ( inputRows.*
     *           , "responseCode" INTEGER          -- the HTTP result
     *           , "httpResponse" VARCHAR(64000) -- the HTTP response (message data)
     *  )
     *
     *    LANGUAGE JAVA
     *    PARAMETER STYLE SYSTEM DEFINED JAVA
     *    NO SQL
     *    EXTERNAL NAME 'class com.sqlstream.plugin.JsonFormatter.jsonFormatter';
     * ;
     *
     * @param inputRows streaming input
     * @param results are the result stream
     * @throws SQLException metadata exceptions
     */
    public static void HttpPost(
            ResultSet inputRows,
            ResultSet optionRows,
            String postDataColumnName,
            PreparedStatement results)
            throws SQLException
    {
//        if (!(inputRows instanceof StreamingResultSet)
//                || !(results instanceof StreamingPreparedStatement))
//        {
//            throw new SQLException(
//                    "HttpPost UDX requires streaming sources only.");
//        }
        tracer.info(" UDX Invoked");

        final HttpPostImpl impl = new HttpPostImpl(inputRows, optionRows, postDataColumnName, results);
        tracer.fine("HttpPost UDX Invoked");
        impl.init();
        impl.run();
        tracer.info("HttpPost UDX Terminated");

    }

    /**
     * HttpPost implementation class.
     * Instantiated and run by static methods in parent class.
     *
     * @author Nigel Thomas
     * @since Aug 7 2017
     */
    protected static class HttpPostImpl extends AbstractBaseUdx
    {
        protected StreamingResultSet in;
        protected StreamingPreparedStatement out;
        protected ResultSet optionRows;

        ResultSetMetaData inputMetaData;

        boolean doPost = true;
        boolean doPassthrough = true;
        String urlString = null;
        String username = null;
        String password = null;
        String userAgent = "HttpPost/1.0";
        String contentType = "application/json";


        int postDataColumnIdx;
        int batchPostColumnIdx;
        int rowtimeColumnIdx;
        int httpResultColumnIdx;
        int httpResponseColumnIdx;

        String postDataColumnName;

        URL url;
        static Verifier verifier = new Verifier();

        int maxBatchSize = 0;       // 0 => no batching, 1 => a batch (array) of 1
        long timeoutMillis;

        int nextRow = 0;
        StringBuilder rowbuffer;
        String separator;

        String responseData;

        Timestamp rowtime = null;
        long rowtimeMillis;
        long lastEmitMillis = 0;

        /**
         * Creates a new Impl using the specified tracer.
         *
         * @param inputRows streaming input
         * @param results streaming output
         * @throws SQLException metadata exceptions,
         */
        protected HttpPostImpl(
                ResultSet inputRows,
                ResultSet optionRows,
                String postDataColumnName,
                PreparedStatement results
        ) throws SQLException
        {
            super(HttpPost.tracer, inputRows, results);
            tracer.info("HttpPostImpl started");

            this.in = (StreamingResultSet) inputRows;
            this.optionRows = optionRows;
            this.out = (StreamingPreparedStatement) results;

            this.postDataColumnName = postDataColumnName;

            createPassMap();    // column index map for


            this.httpResponseColumnIdx = out.getParameterMetaData().getParameterCount();   // JSON response is the last output field
            this.httpResultColumnIdx = httpResponseColumnIdx-1;                            // the status code is the penultimate field


        }

        // borrowed from https://www.mkyong.com/java/how-to-send-http-request-getpost-in-java/
        // with modifications for JSON content
        // String url = "https://selfsolve.apple.com/wcResults.do";
        // String urlParameters = "sn=C02G8416DRJM&cn=&locale=&caller=&num=12345";

        /**
         * send a message batch to the rest API (this.url), and return the JSON response string
         * @param postData
         * @return  - the response code (side effect - save JSON response into class member this.responseData)
         * @throws Exception
         */
        private int sendPost(String postData) {

            int responseCode = -1;
            this.responseData = null;

            try {
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setHostnameVerifier(verifier);

                //TODO move u/p base 64 to startup
                if (username != null && password != null) {
                    String userpass = username + ":" + password;
                    String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userpass.getBytes()));
                    conn.setRequestProperty("Authorization", basicAuth);
                }

                //add request header
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Content-Type", contentType);

                // Send post request
                conn.setDoOutput(true);
                DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
                wr.writeBytes(postData);
                wr.flush();
                wr.close();

                tracer.info("Sending 'POST' request to URL : " + url);
                tracer.info("Post request : " + postData);

                responseCode = conn.getResponseCode();
                tracer.info("Response Code : " + responseCode);

                // log the header responses
                // TODO make these a JSON array in another column?

                JsonObject jsonHeader = new JsonObject();

                for (int n = 1; n < 1000; n++) {
                    String hfk = conn.getHeaderFieldKey(n);
                    if (hfk == null) break;

                    String value = conn.getHeaderField(n);
                    if (value == null) {
                        tracer.info(hfk);
                        continue;
                    }

                    tracer.info(hfk+" : "+ value);
                    jsonHeader.addProperty(hfk,value);

                }

                if (responseCode < 300) {
                    // get response if there is one
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    String inputLine;
                    StringBuffer response = new StringBuffer();

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    tracer.info("Response: " + response);

                    this.responseData = response.toString();
                } else {
                    this.responseData = jsonHeader.toString();
                }


            } catch (MalformedURLException e) {
                tracer.log(Level.SEVERE,"Exception sending", e);

            } catch (IOException e) {
                tracer.log(Level.SEVERE,"Exception sending", e);
            }

            return responseCode;

        }

        protected void startBatch(long millis) {
            rowbuffer = new StringBuilder(1000);
            nextRow = 0;

            if (maxBatchSize > 0) {
                separator = "[";
            } else {
                separator = "";
            }
        }


        protected void emitBatch(long millis) throws SQLException {
            tracer.info("Batch after "+nextRow+" rows");

            // append array terminator if batch size >= 1
            // TODO support batching of non-JSON formats

            String json = rowbuffer.append((maxBatchSize == 0)?"":"]").toString();
            int responseCode = -1;

            out.clearParameters();
            transferColumns(in, out, passList);


            if (doPost) {
                // do the post, and fill in the columns
                responseCode = sendPost(json);
                out.setInt(httpResultColumnIdx, responseCode);
                out.setString(batchPostColumnIdx, json);
                if (responseData != null) {
                    out.setString(httpResponseColumnIdx, responseData);
                }
            }

            out.executeUpdate();

            startBatch(millis);
        }

        protected void emitRowtimeBound(Timestamp rt) throws SQLException {
            if (tracer.isLoggable(Level.FINEST)) {
                tracer.finest("rowtime bound at "+rt.toString());
            }
            out.clearParameters();
            out.setRowtimeBound(rt);
        }

        protected void init()
        {
            startBatch(0L);
        }

        // TODO: possibly add exception handling to these 3 get methods (or remove entirely)
        protected boolean getBoolean(String value) {
            return Boolean.parseBoolean(value);
        }

        protected int getInteger(String value) {
            return Integer.parseInt(value);
        }

        protected long getLong(String value) {
            return Long.parseLong(value);
        }

        /**
         * Read from options stream to get externally configured options
         */
        protected void getOptions() throws SQLException {
            tracer.info("getOptions");

            while (optionRows.next()) {
                // read and parse options
                String optionName = optionRows.getString(1);
                String optionValue = optionRows.getString(2);

                switch (optionName.toUpperCase()) {
                    case "URL":
                        this.urlString = optionValue;
                        break;

                    case "USERNAME":
                        this.username = optionValue;
                        break;

                    case "PASSWORD":
                        this.password = optionValue;

                    case "USER-AGENT":
                        this.userAgent = optionValue;
                        break;

                    case "CONTENT-TYPE":
                        this.contentType = optionValue;
                        break;

                    case "DO-POST":
                        this.doPost = getBoolean(optionValue);
                        break;

                    case "DO-PASSTHROUGH":
                        this.doPassthrough = getBoolean(optionValue);
                        break;

                    case "BATCHSIZE":
                        maxBatchSize = getInteger(optionValue);
                        break;

                    case "TIMEOUT-MILLIS":
                        timeoutMillis = getInteger(optionValue);
                        break;

                    default:
                        tracer.warning("Unrecognized option " + optionName + ": " + optionValue);
                        continue;
                }
                tracer.fine("Parsed option " + optionName + "=" + optionValue);
            }
        }

        protected void run() throws SQLException
        {
            boolean firstRow = true;

            inputMetaData = in.getMetaData();
            postDataColumnIdx = in.findColumn(postDataColumnName);
            batchPostColumnIdx = postDataColumnIdx;

            rowtimeColumnIdx = in.findColumn("ROWTIME");
            tracer.info("ROWTIME col="+rowtimeColumnIdx+", json in col="+postDataColumnIdx + ", json out col="+batchPostColumnIdx +
                    ", result code col="+httpResultColumnIdx+", response message col="+httpResponseColumnIdx);

            if (firstRow) {
                getOptions();
                firstRow = false;
            }

            long millisToTimeout = timeoutMillis;

            try {
                url = new URL(urlString);
            } catch (MalformedURLException e){
                // no point continuing so bail out
                tracer.log(Level.SEVERE, "Malformed URL", e);
                throw new SQLException("Malformed URL "+urlString);
            }

            while (true) {
                RowEvent event = in.nextRowOrRowtime(millisToTimeout);


                switch (event) {
                    case NewRowtimeBound:
                        rowtime = in.getRowtimeBound();
                        rowtimeMillis = rowtime.getTime();

                        if ((rowtimeMillis - lastEmitMillis > timeoutMillis) && maxBatchSize > 1) {
                            // we have waited long enough
                            emitBatch(rowtimeMillis);
                        } else {
                            // don't emit, so send out a rowtime bound instead
                            emitRowtimeBound(rowtime);
                        }

                        break;

                    case NewRow:

                        //transferColumns(in, out, passList);

                        rowtime = in.getTimestamp(rowtimeColumnIdx);
                        tracer.info("row at "+rowtime);

                        String element = in.getString(postDataColumnIdx);

                        if (element != null) {
                            rowbuffer.append(separator).append(element);
                            separator = ",";
                            nextRow++;
                        } else {
                            tracer.info("null row");
                        }

                        if (nextRow >= maxBatchSize) {
                            emitBatch(rowtimeMillis);
                        } else {
                            emitRowtimeBound(rowtime);
                        }
                        break;

                    case Timeout:
                        // we have waited long enough, but have their been any rows?
                        // TODO - how do we know what the rowtime is
                        break;

                    case EndOfStream:
                        return;
                }
            }
        }
    }

    // TODO: make this slightly more secure!

    public static class Verifier implements HostnameVerifier {

        public boolean verify(String arg0, SSLSession arg1) {
            return true;   // mark everything as verified
        }
    }
}

// End HttpPost.java
