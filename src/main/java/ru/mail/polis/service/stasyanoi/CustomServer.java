package ru.mail.polis.service.stasyanoi;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import org.javatuples.Pair;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Mapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;
import static ru.mail.polis.service.stasyanoi.Util.*;

public class CustomServer extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(CustomServer.class);

    private final Map<Integer, String> nodeMapping;
    private final List<String> replicationDefaults = Arrays.asList("1/1", "2/2", "2/3", "3/4", "3/5");
    private final int nodeCount;
    private int nodeNum;
    private final DAO dao;
    private final ExecutorService executorService =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    /**
     * Create custom server.
     *
     * @param dao - DAO to use.
     * @param config - config for server.
     * @param topology - topology of services.
     * @throws IOException - if an IO exception occurs.
     */
    public CustomServer(final DAO dao,
                        final HttpServerConfig config,
                        final Set<String> topology) throws IOException {
        super(config);
        this.nodeCount = topology.size();
        final ArrayList<String> urls = new ArrayList<>(topology);
        urls.sort(String::compareTo);

        final Map<Integer, String> nodeMappingTemp = new TreeMap<>();

        for (int i = 0; i < urls.size(); i++) {
            nodeMappingTemp.put(i, urls.get(i));
            if (urls.get(i).contains(String.valueOf(super.port))) {
                nodeNum = i;
            }
        }

        this.nodeMapping = nodeMappingTemp;

        this.dao = dao;
    }

    private Pair<Integer, Integer> getAckFrom(Request request) {
        int ack;
        int from;
        String replicas = request.getParameter("replicas");
        if (replicas == null) {
            Optional<String[]> ackFrom = replicationDefaults.stream()
                    .map(replic -> replic.split("/"))
                    .filter(strings -> Integer.parseInt(strings[1]) == nodeMapping.size())
                    .findFirst();

            ack = Integer.parseInt(ackFrom.get()[0]);
            from = Integer.parseInt(ackFrom.get()[1]);
        } else {
            replicas = replicas.substring(1);
            ack = Integer.parseInt(Iterables.get(Splitter.on('/').split(replicas), 0));
            from = Integer.parseInt(Iterables.get(Splitter.on('/').split(replicas), 1));
        }

        return new Pair<>(ack, from);
    }

    private Response routeRequest(final Request request, final int node) throws IOException {

        logger.info(nodeMapping.get(nodeNum) + " SEND TO " +nodeMapping.get(node));
        logger.info(nodeMapping.get(nodeNum) + " REQUEST " +request);
        final ConnectionString connectionString = new ConnectionString(nodeMapping.get(node));
        final HttpClient httpClient = new HttpClient(connectionString);
        try {
            Response invoke = httpClient.invoke(request);
            httpClient.close();
            return invoke;
        } catch (InterruptedException | PoolException | HttpException e) {
            return getResponseWithNoBody(Response.INTERNAL_ERROR);
        }
    }

    private int getNode(final byte[] idArray) {
        final int hash = Math.abs(Arrays.hashCode(idArray));

        return hash % nodeCount;
    }

    private void sendErrorInternal(final HttpSession session,
                                         final IOException e) {
        try {
            logger.error(e.getMessage(), e);
            session.sendError("500", e.getMessage());
        } catch (IOException exception) {
            logger.error(e.getMessage(), e);
        }
    }


    /**
     * Get a record by key.
     *
     * @param idParam - key.
     */
    @Path("/v0/entity")
    @RequestMethod(METHOD_GET)
    public void get(final @Param("id") String idParam,
                    final HttpSession session,
                    final Request request) {
        executorService.execute(() -> {
            try {
                logger.info(nodeMapping.get(nodeNum) + " GET EXEC");
                getInternal(idParam, session, request);
            } catch (IOException e) {
                sendErrorInternal(session, e);
            }
        });
    }

    @Path("/v0/entity/rep")
    @RequestMethod(METHOD_GET)
    public void getRep(final @Param("id") String idParam,
                    final HttpSession session) {
        executorService.execute(() -> {
            try {
                getRepInternal(idParam, session);
            } catch (IOException e) {
                sendErrorInternal(session, e);
            }
        });
    }

    private void getRepInternal(String idParam, HttpSession session) throws IOException {
        final Response responseHttp;

        if (idParam == null || idParam.isEmpty()) {
            responseHttp = getResponseWithNoBody(Response.BAD_REQUEST);
        } else {
            final byte[] idArray = idParam.getBytes(StandardCharsets.UTF_8);
            //get id as aligned byte buffer
            final ByteBuffer id = Mapper.fromBytes(idArray);
            responseHttp = getResponseIfIdNotNull(id);
        }

        session.sendResponse(responseHttp);
    }

    private void getInternal(final String idParam,
                             final HttpSession session,
                             final Request request) throws IOException {
        final Response responseHttpTemp;
        //check id param
        logger.info(nodeMapping.get(nodeNum) + " GET");
        Map<Integer, String> tempNodeMapping = new TreeMap<>(nodeMapping);
        if (idParam == null || idParam.isEmpty()) {
            logger.info(nodeMapping.get(nodeNum) + " GET BAD REQUEST");
            responseHttpTemp = getResponseWithNoBody(Response.BAD_REQUEST);
            //remove node
            tempNodeMapping.remove(nodeNum);
        } else {
            logger.info(nodeMapping.get(nodeNum) + " GET PROXY");
            final byte[] idArray = idParam.getBytes(StandardCharsets.UTF_8);
            final int node = getNode(idArray);
            //remove node
            //get id as aligned byte buffer
            final ByteBuffer id = Mapper.fromBytes(idArray);
            //get the response from db
            //remove replication
            Request noRepRequest = getNoRepRequest(request);
            responseHttpTemp = getProxy(noRepRequest, node, id);
            tempNodeMapping.remove(node);
        }

        final Response responseHttp;
        if (request.getParameter("reps", "true").equals("true")) {
            Pair<Integer, Integer> ackFrom = getAckFrom(request);
            logger.info(nodeMapping.get(nodeNum) + " GET REPLICATE START");
            //get from
            int from = ackFrom.getValue1();
            logger.info(nodeMapping.get(nodeNum) + " GET FROM " + from);
            //createNew request
            List<Response> responses = getResponses(request, responseHttpTemp, tempNodeMapping,
                    from - 1);
            logger.info(nodeMapping.get(nodeNum) + " GET RESPONSES " + responses.toString());

            Integer ack = ackFrom.getValue0();
            responseHttp = getEndResponseGet(responses, ack);
            logger.info(nodeMapping.get(nodeNum) + " GET REPLICATE END");
        } else {
            responseHttp = responseHttpTemp;
        }

        session.sendResponse(responseHttp);
    }

    @NotNull
    private Response getEndResponseGet(List<Response> responses, Integer ack) {

        List<Response> goodResponses = responses.stream()
                .filter(response -> response.getStatus() == 200)
                .collect(Collectors.toList());
        List<Response> emptyResponses = responses.stream()
                .filter(response -> response.getStatus() == 404)
                .collect(Collectors.toList());

        final Response responseHttp;
        if (goodResponses.size() >= ack) {
            responseHttp = Response.ok(goodResponses.get(0).getBody());
        } else if (emptyResponses.size() == ack) {
            responseHttp = getResponseWithNoBody(Response.NOT_FOUND);
        } else {
            responseHttp = getResponseWithNoBody(Response.GATEWAY_TIMEOUT);
        }
        return responseHttp;
    }

    private List<Response> getResponses(Request request, Response responseHttpTemp, Map<Integer, String> tempNodeMapping, int from) {
        return getResponsesInternal(responseHttpTemp, tempNodeMapping, from, request);
    }

    private List<Response> getResponsesInternal(Response responseHttpTemp, Map<Integer, String> tempNodeMapping,
                                                int from,
                                                Request request) {


        List<Response> responses = tempNodeMapping.entrySet()
                .stream()
                .limit(from)
                .peek(integerStringEntry -> logger.info(tempNodeMapping.get(nodeNum) + " SEND REQUEST TO " + integerStringEntry.getValue()))
                .map(nodeHost -> new Pair<>
                        (new HttpClient(new ConnectionString(nodeHost.getValue())), getNewRequest(request)))
                .map(clientRequest -> {
                    try {
                        Response invoke = clientRequest.getValue0().invoke(clientRequest.getValue1());
                        clientRequest.getValue0().close();
                        return invoke;
                    } catch (InterruptedException | PoolException | IOException | HttpException e) {
                        return getResponseWithNoBody(Response.INTERNAL_ERROR);
                    }
                })
                .collect(Collectors.toList());
        responses.add(responseHttpTemp);

        return responses;
    }

    @NotNull
    private Request getNewRequest(Request request) {
        String path = request.getPath();
        String queryString = request.getQueryString();
        String newPath = path + "/rep?" + queryString;
        Request requestNew = new Request(request.getMethod(), newPath, true);
        Arrays.stream(request.getHeaders())
                .filter(Objects::nonNull)
                .filter(header -> !header.contains("Host: "))
                .forEach(requestNew::addHeader);
        requestNew.addHeader("Host: localhost:" + super.port);
        requestNew.setBody(request.getBody());
        return requestNew;
    }

    @NotNull
    private Request getNoRepRequest(Request request) {
        String path = request.getPath();
        String queryString = request.getQueryString();
        String newPath;
        if (request.getHeader("reps") == null) {
            newPath = path + "?"+ queryString + "&reps=false";
        } else {
            newPath = path + "?"+ queryString;
        }
        Request noRepRequest = new Request(request.getMethod(), newPath, true);
        Arrays.stream(request.getHeaders())
                .filter(Objects::nonNull)
                .filter(header -> !header.contains("Host: "))
                .forEach(noRepRequest::addHeader);
        noRepRequest.addHeader("Host: localhost:" + super.port);
        noRepRequest.setBody(request.getBody());
        return noRepRequest;
    }

    private Response getProxy(final Request request,
                              final int node,
                              final ByteBuffer id) throws IOException {
        final Response responseHttp;
        if (node != nodeNum) {
            logger.info(nodeMapping.get(nodeNum) + " GET ROUTE");
            responseHttp = routeRequest(request, node);
        } else {
            logger.info(nodeMapping.get(nodeNum) + " GET HERE");
            //replicate here
            responseHttp = getResponseIfIdNotNull(id);
        }
        logger.info(nodeMapping.get(nodeNum) + " GET RESPONSE " + responseHttp);
        return responseHttp;
    }

    @NotNull
    private Response getResponseIfIdNotNull(final ByteBuffer id) throws IOException {
        try {
            final ByteBuffer body = dao.get(id);
            final byte[] bytes = Mapper.toBytes(body);
            return Response.ok(bytes);
        } catch (NoSuchElementException e) {
            //if not found then 404
            return getResponseWithNoBody(Response.NOT_FOUND);
        }
    }

    /**
     * Create or update a record.
     *
     * @param idParam - key of the record.
     * @param request - request with the body.
     */
    @Path("/v0/entity")
    @RequestMethod(METHOD_PUT)
    public void put(final @Param("id") String idParam,
                    final Request request,
                    final HttpSession session) {
        executorService.execute(() -> {
            try {
                putInternal(idParam, request, session);
            } catch (IOException e) {
                sendErrorInternal(session, e);
            }
        });
    }

    @Path("/v0/entity/rep")
    @RequestMethod(METHOD_PUT)
    public void putRep(final @Param("id") String idParam,
                    final Request request,
                    final HttpSession session) {
        executorService.execute(() -> {
            try {
                putRepInternal(idParam, request, session);
            } catch (IOException e) {
                sendErrorInternal(session, e);
            }
        });
    }

    private void putRepInternal(String idParam, Request request, HttpSession session) throws IOException {
        final Response responseHttp;
        if (idParam == null || idParam.isEmpty()) {
            responseHttp = getResponseWithNoBody(Response.BAD_REQUEST);
        } else {
            final byte[] idArray = idParam.getBytes(StandardCharsets.UTF_8);
            final ByteBuffer key = Mapper.fromBytes(idArray);
            final ByteBuffer value = Mapper.fromBytes(request.getBody());
            dao.upsert(key, value);
            responseHttp = getResponseWithNoBody(Response.CREATED);
        }
        session.sendResponse(responseHttp);
    }

    private void putInternal(final String idParam,
                             final Request request,
                             final HttpSession session) throws IOException {

        logger.info(nodeMapping.get(nodeNum) + " PUT BODY: " + Arrays.toString(request.getBody()));
        final Response responseHttpTemp;
        Map<Integer, String> tempNodeMapping = new TreeMap<>(nodeMapping);
        if (idParam == null || idParam.isEmpty()) {
            logger.info(nodeMapping.get(nodeNum) + " PUT BAD REQUEST");
            responseHttpTemp = getResponseWithNoBody(Response.BAD_REQUEST);
            tempNodeMapping.remove(nodeNum);
        } else {
            final byte[] idArray = idParam.getBytes(StandardCharsets.UTF_8);
            final int node = getNode(idArray);
            Request noRepRequest = getNoRepRequest(request);
            responseHttpTemp = putProxy(noRepRequest, idArray, node);
            tempNodeMapping.remove(node);
        }

        final Response responseHttp;
        if (request.getParameter("reps", "true").equals("true")) {
            logger.info(nodeMapping.get(nodeNum) + " REPLICATE");
            logger.info(tempNodeMapping.toString() + " NODES AVAILABLE");
            Pair<Integer, Integer> ackFrom = getAckFrom(request);

            //get from
            int from = ackFrom.getValue1();
            logger.info(nodeMapping.get(nodeNum) + " FROM " + from);

            //createNew request
            List<Response> responses = getResponses(request, responseHttpTemp, tempNodeMapping,
                    --from);
            logger.info(nodeMapping.get(nodeNum) + " RECIEVED REQUESTS FROM " + from);
            logger.info(responses.stream().map(response -> response.getStatus()).collect(Collectors.toList()).toString());

            Integer ack = ackFrom.getValue0();
            responseHttp = getEndResponsePutAndDelete(responses, ack, 201);
        } else {
            responseHttp = responseHttpTemp;
        }

        session.sendResponse(responseHttp);
    }

    @NotNull
    private Response getEndResponsePutAndDelete(List<Response> responses, Integer ack, int status) {
        final Response responseHttp;
        List<Response> goodResponses = responses.stream()
                .filter(response -> response.getStatus() == status)
                .collect(Collectors.toList());
        if (nodeMapping.size() < ack) {
            responseHttp = getResponseWithNoBody(Response.BAD_REQUEST);
        } else {
            if (goodResponses.size() >= ack) {
                if (status == 202) {
                    responseHttp = getResponseWithNoBody(Response.ACCEPTED);
                } else {
                    responseHttp = getResponseWithNoBody(Response.CREATED);
                }
            } else {
                responseHttp = getResponseWithNoBody(Response.GATEWAY_TIMEOUT);
            }
        }
        return responseHttp;
    }

    private Response putProxy(final Request request,
                              final byte[] idArray,
                              final int node) throws IOException {
        final Response responseHttp;
        if (node != nodeNum) {
            logger.info(nodeMapping.get(nodeNum) + " ROUTE");
            responseHttp = routeRequest(request, node);
            logger.info(nodeMapping.get(nodeNum) + " RESPONSE " + responseHttp.getStatus());
        } else {
            logger.info(nodeMapping.get(nodeNum) + " HERE");
            //replicate here
            final ByteBuffer key = Mapper.fromBytes(idArray);
            final ByteBuffer value = Mapper.fromBytes(request.getBody());
            dao.upsert(key, value);
            responseHttp = getResponseWithNoBody(Response.CREATED);
            logger.info(nodeMapping.get(nodeNum) + " RESPONSE " + responseHttp.getStatus());

        }
        return responseHttp;
    }

    /**
     * Delete a record.
     *
     * @param idParam - key of the record to delete.
     */
    @Path("/v0/entity")
    @RequestMethod(METHOD_DELETE)
    public void delete(final @Param("id") String idParam,
                       final Request request,
                       final HttpSession session) {
        executorService.execute(() -> {
            try {
                deleteInternal(idParam, request, session);
            } catch (IOException e) {
                sendErrorInternal(session, e);
            }
        });
    }

    @Path("/v0/entity/rep")
    @RequestMethod(METHOD_DELETE)
    public void deleteRep(final @Param("id") String idParam,
                       final HttpSession session) {
        executorService.execute(() -> {
            try {
                deleteRepInternal(idParam, session);
            } catch (IOException e) {
                sendErrorInternal(session, e);
            }
        });
    }

    private void deleteRepInternal(String idParam, HttpSession session) throws IOException {
        final Response responseHttp;
        if (idParam == null || idParam.isEmpty()) {
            responseHttp = getResponseWithNoBody(Response.BAD_REQUEST);
        } else {
            final byte[] idArray = idParam.getBytes(StandardCharsets.UTF_8);
            final ByteBuffer key = Mapper.fromBytes(idArray);
            dao.remove(key);
            responseHttp = getResponseWithNoBody(Response.ACCEPTED);
        }
        session.sendResponse(responseHttp);
    }

    private void deleteInternal(final String idParam,
                                final Request request,
                                final HttpSession session) throws IOException {
        final Response responseHttpTemp;
        Map<Integer, String> tempNodeMapping = new TreeMap<>(nodeMapping);
        if (idParam == null || idParam.isEmpty()) {
            responseHttpTemp = getResponseWithNoBody(Response.BAD_REQUEST);
            tempNodeMapping.remove(nodeNum);
        } else {
            final byte[] idArray = idParam.getBytes(StandardCharsets.UTF_8);
            final int node = getNode(idArray);
            Request noRepRequest = getNoRepRequest(request);
            responseHttpTemp = deleteProxy(noRepRequest, idArray, node);
            tempNodeMapping.remove(node);
        }

        final Response responseHttp;
        if (request.getParameter("reps", "true").equals("true")) {
            Pair<Integer, Integer> ackFrom = getAckFrom(request);

            //get from
            int from = ackFrom.getValue1();
            //createNew request
            List<Response> responses = getResponses(request, responseHttpTemp, tempNodeMapping,
                    --from);

            Integer ack = ackFrom.getValue0();
            responseHttp = getEndResponsePutAndDelete(responses, ack, 202);
        } else {
            responseHttp = responseHttpTemp;
        }


        session.sendResponse(responseHttp);
    }

    private Response deleteProxy(final Request request,
                                 final byte[] idArray,
                                 final int node) throws IOException {
        final Response responseHttp;
        if (node != nodeNum) {
            responseHttp = routeRequest(request, node);
        } else {
            //replicate here
            final ByteBuffer key = Mapper.fromBytes(idArray);
            dao.remove(key);
            responseHttp = getResponseWithNoBody(Response.ACCEPTED);
        }
        return responseHttp;
    }

    /**
     * Default handler for unmapped requests.
     *
     * @param request - unmapped request
     * @param session - session object
     * @throws IOException - if input|output exceptions occur within the method
     */
    @Override
    public void handleDefault(final Request request, final HttpSession session) throws IOException {
        final Response response = getResponseWithNoBody(Response.BAD_REQUEST);
        session.sendResponse(response);
    }

    /**
     * Status check.
     *
     * @return Response with status.
     */
    @Path("/v0/status")
    @RequestMethod(METHOD_GET)
    public Response status() {
        return getResponseWithNoBody(Response.OK);
    }

    @Override
    public synchronized void start() {
        super.start();
        dao.open();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
            executorService.shutdown();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
