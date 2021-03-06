package cool.graph.client.server

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import cool.graph.bugsnag.GraphCoolRequest
import cool.graph.client.FeatureMetric.FeatureMetric
import cool.graph.client.database.DeferredResolverProvider
import cool.graph.client.metrics.ApiMetricsMiddleware
import cool.graph.client.{ClientInjector, ProjectLockdownMiddleware, UserContext}
import cool.graph.shared.BackendSharedMetrics
import cool.graph.shared.logging.RequestTookVeryLongException
import cool.graph.util.ErrorHandlerFactory
import sangria.execution.{ErrorWithResolver, Executor, QueryAnalysisError}
import spray.json.{JsArray, JsValue}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

trait GraphQlRequestHandler {
  def handle(graphQlRequest: GraphQlRequest): Future[(StatusCode, JsValue)]

  def healthCheck: Future[Unit]
}

case class GraphQlRequestHandlerImpl[ConnectionOutputType](
    errorHandlerFactory: ErrorHandlerFactory,
    log: String => Unit,
    apiVersionMetric: FeatureMetric,
    apiMetricsMiddleware: ApiMetricsMiddleware,
    deferredResolver: DeferredResolverProvider[ConnectionOutputType, UserContext]
)(implicit ec: ExecutionContext, injector: ClientInjector)
    extends GraphQlRequestHandler {
  import cool.graph.shared.schema.JsonMarshalling._

  val isReportLongRequestsEnabled = sys.env.get("REPORT_LONG_REQUESTS_DISABLED") match {
    case Some("1") => false
    case _         => true
  }

  override def handle(graphQlRequest: GraphQlRequest): Future[(StatusCode, JsValue)] = {
    val jsonResult = if (!graphQlRequest.isBatch) {
      handleQuery(request = graphQlRequest, query = graphQlRequest.queries.head)
    } else {
      val results: Seq[Future[JsValue]] = graphQlRequest.queries.map(query => handleQuery(graphQlRequest, query))
      Future.sequence(results).map(results => JsArray(results.toVector))
    }
    jsonResult.map { json =>
      reportDuration(graphQlRequest)
      OK -> json
    }
  }

  def handleQuery(
      request: GraphQlRequest,
      query: GraphQlQuery
  ): Future[JsValue] = {
    val (sangriaErrorHandler, unhandledErrorLogger) = errorHandlerFactory.sangriaAndUnhandledHandlers(
      requestId = request.id,
      query = query.queryString,
      variables = query.variables,
      clientId = Some(request.projectWithClientId.clientId),
      projectId = Some(request.projectWithClientId.id)
    )

    request.logger.query(query.queryString, query.variables.prettyPrint)

    val context = UserContext.fetchUserProjectWithClientId(
      authenticatedRequest = request.authorization,
      requestId = request.id,
      requestIp = request.ip,
      project = request.projectWithClientId,
      log = log,
      queryAst = Some(query.query)
    )(injector.toScaldi)

    context.addFeatureMetric(apiVersionMetric)
    context.graphcoolHeader = request.sourceHeader

    val result = Executor.execute(
      schema = request.schema,
      queryAst = query.query,
      userContext = context,
      variables = query.variables,
      exceptionHandler = sangriaErrorHandler,
      operationName = query.operationName,
      deferredResolver = deferredResolver,
      middleware = List(apiMetricsMiddleware, ProjectLockdownMiddleware(request.project))
    )

    result.recover {
      case error: QueryAnalysisError =>
        error.resolveError

      case error: ErrorWithResolver =>
        unhandledErrorLogger(error)
        error.resolveError

      case error: Throwable ⇒
        unhandledErrorLogger(error)._2
    }
  }

  def reportDuration(request: GraphQlRequest): Unit = {
    val duration = System.currentTimeMillis() - request.logger.requestBeginningTime
    if (duration >= 2000 && isReportLongRequestsEnabled) {
      val requestForBugsnag = GraphCoolRequest(
        requestId = request.id,
        clientId = Some(request.projectWithClientId.clientId),
        projectId = Some(request.project.id),
        query = request.queries.map(_.queryString).mkString("\n"),
        variables = request.queries.map(_.variables.toString).mkString("\n")
      )
      injector.bugsnagger.report(RequestTookVeryLongException(duration), requestForBugsnag)
    }
    BackendSharedMetrics.requestDuration.record(duration, Seq(request.project.id))
  }

  override def healthCheck: Future[Unit] = Future.successful(())
}
