/*
 * (C) Copyright 2016 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Gabriel Barata <gbarata@nuxeo.com>
 */
package org.nuxeo.ecm.restapi.server.jaxrs.search;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.lang.StringUtils;
import org.nuxeo.ecm.automation.core.util.DocumentHelper;
import org.nuxeo.ecm.automation.core.util.Properties;
import org.nuxeo.ecm.automation.server.jaxrs.RestOperationException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.SortInfo;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.PropertyNotFoundException;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.ecm.platform.search.core.InvalidSearchParameterException;
import org.nuxeo.ecm.platform.search.core.SavedSearch;
import org.nuxeo.ecm.platform.search.core.SavedSearchConstants;
import org.nuxeo.ecm.platform.search.core.SavedSearchRequest;
import org.nuxeo.ecm.platform.search.core.SavedSearchService;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 8.3 Search endpoint to perform queries via rest api, with support to save and execute saved search queries.
 */
@WebObject(type = "search")
public class SearchObject extends QueryExecutor {

    private static final String APPLICATION_JSON_NXENTITY = "application/json+nxentity";

    public static final String SAVED_SEARCHES_PAGE_PROVIDER = "SAVED_SEARCHES_ALL";

    public static final String SAVED_SEARCHES_PAGE_PROVIDER_PARAMS = "SAVED_SEARCHES_ALL_PAGE_PROVIDER";

    public static final String PAGE_PROVIDER_NAME_PARAM = "pageProvider";

    protected SavedSearchService savedSearchService;

    @Override
    public void initialize(Object... args) {
        initExecutor();
        savedSearchService = Framework.getService(SavedSearchService.class);
    }

    @GET
    @Path("lang/{queryLanguage}/execute")
    public Object doQueryByLang(@Context UriInfo uriInfo, @PathParam("queryLanguage") String queryLanguage)
            throws RestOperationException {
        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        return queryByLang(queryLanguage, queryParams);
    }

    @GET
    @Path("pp/{pageProviderName}/execute")
    public Object doQueryByPageProvider(@Context UriInfo uriInfo, @PathParam("pageProviderName") String pageProviderName)
            throws RestOperationException {
        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        return queryByPageProvider(pageProviderName, queryParams);
    }

    @GET
    @Path("pp/{pageProviderName}")
    public Object doGetPageProviderDefinition(@PathParam("pageProviderName") String pageProviderName)
            throws RestOperationException, IOException {
        return buildResponse(Response.Status.OK, MediaType.APPLICATION_JSON,
                getPageProviderDefinition(pageProviderName));
    }

    @GET
    @Path("saved")
    public List<SavedSearch> doGetSavedSearches(@Context UriInfo uriInfo) throws RestOperationException {
        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        DocumentModelList results = queryParams.containsKey(PAGE_PROVIDER_NAME_PARAM) ? queryByPageProvider(
                SAVED_SEARCHES_PAGE_PROVIDER_PARAMS, queryParams) : queryByPageProvider(SAVED_SEARCHES_PAGE_PROVIDER,
                queryParams);
        List<SavedSearch> savedSearches = new ArrayList<>(results.size());
        for (DocumentModel doc : results) {
            savedSearches.add(doc.getAdapter(SavedSearch.class));
        }
        return savedSearches;
    }

    @POST
    @Path("saved")
    @Consumes({ APPLICATION_JSON_NXENTITY, "application/json" })
    public Response doSaveSearch(SavedSearchRequest request) throws RestOperationException {
        try {
            SavedSearch search = savedSearchService.createSavedSearch(ctx.getCoreSession(), request.getTitle(),
                    request.getQueryParams(), null, request.getQuery(), request.getQueryLanguage(),
                    request.getPageProviderName(), request.getPageSize(), request.getCurrentPageIndex(),
                    request.getMaxResults(), request.getSortBy(), request.getSortOrder(), request.getContentViewData());
            setSaveSearchParams(request.getNamedParams(), search);
            return Response.ok(savedSearchService.saveSavedSearch(ctx.getCoreSession(), search)).build();
        } catch (InvalidSearchParameterException | IOException e) {
            RestOperationException err = new RestOperationException(e.getMessage());
            err.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            throw err;
        }
    }

    @GET
    @Path("saved/{id}")
    public Response doGetSavedSearch(@PathParam("id") String id) throws RestOperationException {
        SavedSearch search;
        try {
            search = savedSearchService.getSavedSearch(ctx.getCoreSession(), id);
        } catch (DocumentNotFoundException e) {
            RestOperationException err = new RestOperationException("unknown id: " + e.getMessage());
            err.setStatus(HttpServletResponse.SC_NOT_FOUND);
            throw err;
        }
        if (search == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(search).build();
    }

    @PUT
    @Path("saved/{id}")
    @Consumes({ APPLICATION_JSON_NXENTITY, "application/json" })
    public Response doUpdateSavedSearch(SavedSearchRequest request, @PathParam("id") String id)
            throws RestOperationException {
        SavedSearch search;
        try {
            search = savedSearchService.getSavedSearch(ctx.getCoreSession(), id);
        } catch (DocumentNotFoundException e) {
            RestOperationException err = new RestOperationException("unknown id: " + e.getMessage());
            err.setStatus(HttpServletResponse.SC_NOT_FOUND);
            throw err;
        }
        if (search == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        search.setTitle(request.getTitle());
        search.setQueryParams(request.getQueryParams());
        search.setQuery(request.getQuery());
        search.setQueryLanguage(request.getQueryLanguage());
        search.setPageProviderName(request.getPageProviderName());
        search.setPageSize(request.getPageSize());
        search.setCurrentPageIndex(request.getCurrentPageIndex());
        search.setMaxResults(request.getMaxResults());
        search.setSortBy(request.getSortBy());
        search.setSortOrder(request.getSortOrder());
        search.setContentViewData(request.getContentViewData());
        try {
            setSaveSearchParams(request.getNamedParams(), search);
            search = savedSearchService.saveSavedSearch(ctx.getCoreSession(), search);
        } catch (InvalidSearchParameterException | IOException e) {
            RestOperationException err = new RestOperationException(e.getMessage());
            err.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            throw err;
        }
        return Response.ok(search).build();
    }

    @DELETE
    @Path("saved/{id}")
    public Response doDeleteSavedSearch(@PathParam("id") String id) throws RestOperationException {
        try {
            SavedSearch search = savedSearchService.getSavedSearch(ctx.getCoreSession(), id);
            savedSearchService.deleteSavedSearch(ctx.getCoreSession(), search);
        } catch (DocumentNotFoundException e) {
            RestOperationException err = new RestOperationException(e.getMessage());
            err.setStatus(HttpServletResponse.SC_NOT_FOUND);
            throw err;
        }
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @GET
    @Path("saved/{id}/execute")
    public Object doExecuteSavedSearch(@PathParam("id") String id, @Context UriInfo uriInfo)
            throws RestOperationException {
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        SavedSearch search = savedSearchService.getSavedSearch(ctx.getCoreSession(), id);
        if (search == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return executeSavedSearch(search, params);
    }

    protected void setSaveSearchParams(Map<String, String> params, SavedSearch search) throws IOException {
        Map<String, String> namedParams = new HashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                try {
                    Property prop = search.getDocument().getProperty(key);
                    DocumentHelper.setProperty(search.getDocument().getCoreSession(), search.getDocument(), key, value,
                            true);
                } catch (PropertyNotFoundException e) {
                    namedParams.put(key, value);
                }
            }
        }
        search.setNamedParams(namedParams);
    }

    protected DocumentModelList executeSavedSearch(SavedSearch search, MultivaluedMap<String, String> params)
            throws RestOperationException {
        Long pageSize = getPageSize(params);
        Long currentPageIndex = getCurrentPageIndex(params);
        Long maxResults = getMaxResults(params);
        List<SortInfo> sortInfo = getSortInfo(params);

        if (!StringUtils.isEmpty(search.getPageProviderName())) {
            return querySavedSearchByPageProvider(
                    search.getPageProviderName(),
                    pageSize != null ? pageSize : search.getPageSize(),
                    currentPageIndex != null ? currentPageIndex : search.getCurrentPageIndex(),
                    search.getQueryParams(),
                    search.getNamedParams(),
                    sortInfo != null ? sortInfo : getSortInfo(search.getSortBy(), search.getSortOrder()),
                    search.getDocument().getType() != SavedSearchConstants.PARAMETERIZED_SAVED_SEARCH_TYPE_NAME ? search.getDocument()
                            : null);
        } else if (!StringUtils.isEmpty(search.getQuery()) && !StringUtils.isEmpty(search.getQueryLanguage())) {
            return querySavedSearchByLang(search.getQueryLanguage(), search.getQuery(), pageSize != null ? pageSize
                    : search.getPageSize(), currentPageIndex != null ? currentPageIndex : search.getCurrentPageIndex(),
                    maxResults != null ? maxResults : search.getMaxResults(), search.getQueryParams(),
                    search.getNamedParams(),
                    sortInfo != null ? sortInfo : getSortInfo(search.getSortBy(), search.getSortOrder()));
        } else {
            return null;
        }
    }

    protected DocumentModelList querySavedSearchByLang(String queryLanguage, String query, Long pageSize,
            Long currentPageIndex, Long maxResults, String orderedParams, Map<String, String> namedParameters,
            List<SortInfo> sortInfo) throws RestOperationException {
        Properties namedParametersProps = getNamedParameters(namedParameters);
        Object[] parameters = replaceParameterPattern(new Object[] { orderedParams });
        Map<String, Serializable> props = getProperties();

        DocumentModel searchDocumentModel = getSearchDocumentModel(ctx.getCoreSession(), pageProviderService, null,
                namedParametersProps);

        return queryByLang(query, pageSize, currentPageIndex, maxResults, sortInfo, parameters, props,
                searchDocumentModel);
    }

    protected DocumentModelList querySavedSearchByPageProvider(String pageProviderName, Long pageSize,
            Long currentPageIndex, String orderedParams, Map<String, String> namedParameters, List<SortInfo> sortInfo,
            DocumentModel searchDocumentModel) throws RestOperationException {
        Properties namedParametersProps = getNamedParameters(namedParameters);
        Object[] parameters = orderedParams != null ? replaceParameterPattern(new Object[] { orderedParams })
                : new Object[0];
        Map<String, Serializable> props = getProperties();

        DocumentModel documentModel;
        if (searchDocumentModel == null) {
            documentModel = getSearchDocumentModel(ctx.getCoreSession(), pageProviderService, pageProviderName,
                    namedParametersProps);
        } else {
            documentModel = searchDocumentModel;
            if (namedParametersProps.size() > 0) {
                documentModel.putContextData(PageProviderService.NAMED_PARAMETERS, namedParametersProps);
            }
        }

        return queryByPageProvider(pageProviderName, pageSize, currentPageIndex, sortInfo, parameters, props,
                documentModel);
    }

}