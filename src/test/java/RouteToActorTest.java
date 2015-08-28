import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.dispatch.Mapper;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.server.HttpApp;
import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.server.RequestVal;
import akka.http.javadsl.server.RequestVals;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.RouteResult;
import akka.pattern.Patterns;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RouteToActorTest {

  private ActorSystem system;
  private Materializer mat;
  private JavaTestKit probe;

  public static class MyAppService extends HttpApp {

    private ActorRef apiActor;

    public MyAppService(ActorRef apiActor) {
      this.apiActor = apiActor;
    }

    RequestVal<Request> requestVal = RequestVals.entityAs(Jackson.jsonAs(Request.class));

    @Override
    public Route createRoute() {
      return route(pathPrefix("recharge").route(
        path("single").route(post(handleWithAsync1(requestVal, this::asyncSendToActor)))));
    }

    public Future<RouteResult> asyncSendToActor(final RequestContext ctx, Request request) {
      Mapper<Object, RouteResult> func = new Mapper<Object, RouteResult>() {
        @Override
        public RouteResult apply(Object apiResponse) {
          return ctx.complete("Test " + apiResponse);
        }
      };
      return sendToActor(request, ctx.executionContext()).map(func, ctx.executionContext());
    }

    public Future<Object> sendToActor(Request request, ExecutionContext ec) {
      return Patterns.ask(apiActor, request, 3000);
    }
  }

  public static class Request {
    public int id;
  }

  @Before
  public void before() {
    system = ActorSystem.create();
    probe = new JavaTestKit(system);
    mat = ActorMaterializer.create(system);
  }

  @Test
  public void routeTest() throws Exception {
    new MyAppService(probe.getRef()).bindRoute("localhost", 8080, system);

    HttpRequest httpRequest = HttpRequest.create()
      .withUri("http://localhost:8080/recharge/single")
      .withMethod(HttpMethods.POST)
      .withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "{ \"id\": 123 }");

    Future<HttpResponse> responseFuture = Http.get(system).singleRequest(httpRequest, mat);
    Request request = probe.expectMsgClass(Request.class);
    assertEquals(123, request.id);

    probe.getLastSender().tell("api_response", ActorRef.noSender());

    HttpResponse response = Await.result(responseFuture, Duration.create(1, TimeUnit.SECONDS));
    Future<ByteString> bodyFuture = response.entity().getDataBytes().runFold(ByteString.empty(), (a, b) -> a.concat(b), mat);
    ByteString body = Await.result(bodyFuture, Duration.create(100, TimeUnit.SECONDS));
    assertEquals("Test api_response", body.utf8String());
  }

  @After
  public void after() {
    system.shutdown();
    system.awaitTermination();
  }

}
