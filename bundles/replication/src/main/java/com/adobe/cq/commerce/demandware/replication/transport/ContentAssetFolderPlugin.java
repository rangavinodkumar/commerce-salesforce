/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Copyright 2019 Adobe Systems Incorporated
 ~
 ~ Licensed under the Apache License, Version 2.0 (the "License");
 ~ you may not use this file except in compliance with the License.
 ~ You may obtain a copy of the License at
 ~
 ~     http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing, software
 ~ distributed under the License is distributed on an "AS IS" BASIS,
 ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ~ See the License for the specific language governing permissions and
 ~ limitations under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package com.adobe.cq.commerce.demandware.replication.transport;

import com.adobe.cq.commerce.demandware.DemandwareClient;
import com.adobe.cq.commerce.demandware.DemandwareCommerceConstants;
import com.adobe.cq.commerce.demandware.InstanceIdProvider;
import com.adobe.cq.commerce.demandware.replication.TransportHandlerPlugin;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationLog;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.sling.commons.json.JSONArray;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;
import org.osgi.framework.Constants;

/**
 * <code>TransportHandlerPlugin</code> to associate content assets to one or more folders within the Demandware
 * content library.
 */
@Component(label = "Demandware TransportHandler Plugin Content Assets Folder", metatype = true, immediate = true)
@Service(value = TransportHandlerPlugin.class)
@Properties({@Property(name = TransportHandlerPlugin.PN_TASK, value = "ContentAssetFolderPlugin", propertyPrivate = true),
        @Property(name = Constants.SERVICE_RANKING, intValue = 11)})
public class ContentAssetFolderPlugin extends AbstractOCAPITransportPlugin {
    
    @Reference
    private InstanceIdProvider instanceIdProvider;

    @Override
    String getContentType() {
        return "content-asset";
    }
    
    // TODO currently all folders assigned in AEM are pushed to DWRE, but deletions are not handled
    // TODO > should be get all folders first, check for new (and only push new) and remove deleted
    @Override
    public boolean deliver(JSONObject delivery, AgentConfig config, ReplicationLog log,
                           ReplicationAction action) throws ReplicationException {
        final String id = delivery.optString(DemandwareCommerceConstants.ATTR_ID,
                StringUtils.substringAfterLast(action.getPath(), "/"));
        
        // check if we have some folder assignments
        if (delivery.has(DemandwareCommerceConstants.ATTR_FOLDER)) {
            boolean defaultFolder = true;
            HttpClient httpClient = getHttpClient(config, log);
            try {
                JSONArray folders = delivery.getJSONArray(DemandwareCommerceConstants.ATTR_FOLDER);
                for (int i = 0; i < folders.length(); i++) {
                    final String folder = StringUtils.trimToNull(folders.getString(i));
                    if (StringUtils.isNotEmpty(folder)) {
                        log.info("Assign content asset %s to folder %s (%s)", id, folder, action.getType().getName());
                        
                        // construct URL for folder assignment
                        final String instanceId = instanceIdProvider.getInstanceId(config);
                        final String endpoint = clientProvider.getClientForSpecificInstance(instanceId).getEndpoint();
                        final String transportUriBuilder = DemandwareClient.DEFAULT_SCHEMA + endpoint + getOCApiPath() + getOCApiVersion() +
                                constructEndpointURL(delivery.getString(DemandwareCommerceConstants.ATTR_API_ENDPOINT), folder, delivery);
                        final RequestBuilder requestBuilder = RequestBuilder.put();
                        requestBuilder.setUri(transportUriBuilder);
                        requestBuilder.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
                        
                        // add json body, first folder will always be the default folder
                        final JSONObject bodyJSON = new JSONObject();
                        bodyJSON.put("default", defaultFolder);
                        requestBuilder.setEntity(new StringEntity(bodyJSON.toString(), ContentType.APPLICATION_JSON));
                        
                        HttpResponse response = null;
                        response = executeRequest(httpClient, requestBuilder.build(), log);
                        if (isRequestSuccessful(response)) {
                            log.info("Content asset %s assigned to folder %s", id, folder);
                        } else {
                            log.warn("Content asset %s NOT assigned to folder %s", id, folder);
                        }
                        HttpClientUtils.closeQuietly(response);
                        defaultFolder = false;
                    }
                }
            } catch (JSONException e) {
                throw new ReplicationException(e);
            } finally {
                HttpClientUtils.closeQuietly(httpClient);
            }
        } else {
            log.debug("No folder assignments find, nothing to do");
        }
        return true;
    }
    
    private String constructEndpointURL(String endpointUrl, String folder, JSONObject delivery) {
        endpointUrl = StringUtils.appendIfMissing(StringUtils.replace(endpointUrl, "/content/",
                "/folder_assignments/"), "/" + folder);
        return super.constructEndpointURL(endpointUrl, delivery);
    }
}
