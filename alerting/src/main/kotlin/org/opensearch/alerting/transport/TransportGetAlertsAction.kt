/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.opensearch.action.ActionListener
import org.opensearch.action.ActionRequest
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.alerting.alerts.AlertIndices
import org.opensearch.alerting.opensearchapi.addFilter
import org.opensearch.alerting.opensearchapi.suspendUntil
import org.opensearch.alerting.settings.AlertingSettings
import org.opensearch.alerting.util.AlertingException
import org.opensearch.alerting.util.use
import org.opensearch.client.Client
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.inject.Inject
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.XContentHelper
import org.opensearch.common.xcontent.XContentParserUtils
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.alerting.action.AlertingActions
import org.opensearch.commons.alerting.action.GetAlertsRequest
import org.opensearch.commons.alerting.action.GetAlertsResponse
import org.opensearch.commons.alerting.model.Alert
import org.opensearch.commons.alerting.model.Monitor
import org.opensearch.commons.alerting.model.ScheduledJob
import org.opensearch.commons.authuser.User
import org.opensearch.commons.utils.logger
import org.opensearch.commons.utils.recreateObject
import org.opensearch.core.xcontent.NamedXContentRegistry
import org.opensearch.core.xcontent.XContentParser
import org.opensearch.index.query.BoolQueryBuilder
import org.opensearch.index.query.Operator
import org.opensearch.index.query.QueryBuilders
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.search.sort.SortBuilders
import org.opensearch.search.sort.SortOrder
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService
import java.io.IOException

private val log = LogManager.getLogger(TransportGetAlertsAction::class.java)
private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

class TransportGetAlertsAction @Inject constructor(
    transportService: TransportService,
    val client: Client,
    clusterService: ClusterService,
    actionFilters: ActionFilters,
    val settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<ActionRequest, GetAlertsResponse>(
    AlertingActions.GET_ALERTS_ACTION_NAME,
    transportService,
    actionFilters,
    ::GetAlertsRequest
),
    SecureTransportAction {

    @Volatile
    override var filterByEnabled = AlertingSettings.FILTER_BY_BACKEND_ROLES.get(settings)

    init {
        listenFilterBySettingChange(clusterService)
    }

    override fun doExecute(
        task: Task,
        request: ActionRequest,
        actionListener: ActionListener<GetAlertsResponse>
    ) {
        val getAlertsRequest = request as? GetAlertsRequest
            ?: recreateObject(request) { GetAlertsRequest(it) }
        val user = readUserFromThreadContext(client)

        val tableProp = getAlertsRequest.table
        val sortBuilder = SortBuilders
            .fieldSort(tableProp.sortString)
            .order(SortOrder.fromString(tableProp.sortOrder))
        if (!tableProp.missing.isNullOrBlank()) {
            sortBuilder.missing(tableProp.missing)
        }

        val queryBuilder = QueryBuilders.boolQuery()

        if (getAlertsRequest.severityLevel != "ALL") {
            queryBuilder.filter(QueryBuilders.termQuery("severity", getAlertsRequest.severityLevel))
        }

        if (getAlertsRequest.alertState != "ALL") {
            queryBuilder.filter(QueryBuilders.termQuery("state", getAlertsRequest.alertState))
        }

        if (getAlertsRequest.alertIds.isNullOrEmpty() == false) {
            queryBuilder.filter(QueryBuilders.termsQuery("_id", getAlertsRequest.alertIds))
        }

        if (getAlertsRequest.monitorId != null) {
            queryBuilder.filter(QueryBuilders.termQuery("monitor_id", getAlertsRequest.monitorId))
        } else if (getAlertsRequest.monitorIds.isNullOrEmpty() == false) {
            queryBuilder.filter(QueryBuilders.termsQuery("monitor_id", getAlertsRequest.monitorIds))
        }
        if (getAlertsRequest.workflowIds.isNullOrEmpty() == false) {
            val bqb: BoolQueryBuilder = QueryBuilders.boolQuery()
            queryBuilder.must(QueryBuilders.termsQuery("workflow_id", getAlertsRequest.workflowIds))
            if (getAlertsRequest.monitorId.isNullOrEmpty() && getAlertsRequest.monitorIds.isNullOrEmpty()) {
                queryBuilder.must(QueryBuilders.termQuery("monitor_id", ""))
            }
        }
        if (!tableProp.searchString.isNullOrBlank()) {
            queryBuilder
                .must(
                    QueryBuilders
                        .queryStringQuery(tableProp.searchString)
                        .defaultOperator(Operator.AND)
                        .field("monitor_name")
                        .field("trigger_name")
                )
        }
        val searchSourceBuilder = SearchSourceBuilder()
            .version(true)
            .seqNoAndPrimaryTerm(true)
            .query(queryBuilder)
            .sort(sortBuilder)
            .size(tableProp.size)
            .from(tableProp.startIndex)

        client.threadPool().threadContext.stashContext().use {
            scope.launch {
                try {
                    val alertIndex = resolveAlertsIndexName(getAlertsRequest)
                    getAlerts(alertIndex, searchSourceBuilder, actionListener, user)
                } catch (t: Exception) {
                    log.error("Failed to get alerts", t)
                    if (t is AlertingException) {
                        actionListener.onFailure(t)
                    } else {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                }
            }
        }
    }

    /** Precedence order for resolving alert index to be queried:
     1. alertIndex param.
     2. alert index mentioned in monitor data sources.
     3. Default alert indices pattern
     */
    suspend fun resolveAlertsIndexName(getAlertsRequest: GetAlertsRequest): String {
        var alertIndex = AlertIndices.ALL_ALERT_INDEX_PATTERN
        if (!getAlertsRequest.alertIndex.isNullOrEmpty()) {
            alertIndex = getAlertsRequest.alertIndex!!
        } else if (getAlertsRequest.monitorId.isNullOrEmpty() == false) {
            val retrievedMonitor = getMonitor(getAlertsRequest)
            if (retrievedMonitor != null) {
                alertIndex = retrievedMonitor.dataSources.alertsIndex
            }
        }
        return if (alertIndex == AlertIndices.ALERT_INDEX)
            AlertIndices.ALL_ALERT_INDEX_PATTERN
        else
            alertIndex
    }

    private suspend fun getMonitor(getAlertsRequest: GetAlertsRequest): Monitor? {
        val getRequest = GetRequest(ScheduledJob.SCHEDULED_JOBS_INDEX, getAlertsRequest.monitorId!!)
        try {
            val getResponse: GetResponse = client.suspendUntil { client.get(getRequest, it) }
            if (!getResponse.isExists) {
                return null
            }
            val xcp = XContentHelper.createParser(
                xContentRegistry, LoggingDeprecationHandler.INSTANCE,
                getResponse.sourceAsBytesRef, XContentType.JSON
            )
            return ScheduledJob.parse(xcp, getResponse.id, getResponse.version) as Monitor
        } catch (t: Exception) {
            log.error("Failure in fetching monitor ${getAlertsRequest.monitorId} to resolve alert index in get alerts action", t)
            return null
        }
    }

    fun getAlerts(
        alertIndex: String,
        searchSourceBuilder: SearchSourceBuilder,
        actionListener: ActionListener<GetAlertsResponse>,
        user: User?
    ) {
        // user is null when: 1/ security is disabled. 2/when user is super-admin.
        if (user == null) {
            // user is null when: 1/ security is disabled. 2/when user is super-admin.
            search(alertIndex, searchSourceBuilder, actionListener)
        } else if (!doFilterForUser(user)) {
            // security is enabled and filterby is disabled.
            search(alertIndex, searchSourceBuilder, actionListener)
        } else {
            // security is enabled and filterby is enabled.
            try {
                log.info("Filtering result by: ${user.backendRoles}")
                addFilter(user, searchSourceBuilder, "monitor_user.backend_roles.keyword")
                search(alertIndex, searchSourceBuilder, actionListener)
            } catch (ex: IOException) {
                actionListener.onFailure(AlertingException.wrap(ex))
            }
        }
    }

    fun search(alertIndex: String, searchSourceBuilder: SearchSourceBuilder, actionListener: ActionListener<GetAlertsResponse>) {
        val searchRequest = SearchRequest()
            .indices(alertIndex)
            .source(searchSourceBuilder)

        client.search(
            searchRequest,
            object : ActionListener<SearchResponse> {
                override fun onResponse(response: SearchResponse) {
                    val totalAlertCount = response.hits.totalHits?.value?.toInt()
                    val alerts = response.hits.map { hit ->
                        val xcp = XContentHelper.createParser(
                            xContentRegistry,
                            LoggingDeprecationHandler.INSTANCE,
                            hit.sourceRef,
                            XContentType.JSON
                        )
                        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, xcp.nextToken(), xcp)
                        val alert = Alert.parse(xcp, hit.id, hit.version)
                        alert
                    }
                    actionListener.onResponse(GetAlertsResponse(alerts, totalAlertCount))
                }

                override fun onFailure(t: Exception) {
                    actionListener.onFailure(t)
                }
            }
        )
    }
}
