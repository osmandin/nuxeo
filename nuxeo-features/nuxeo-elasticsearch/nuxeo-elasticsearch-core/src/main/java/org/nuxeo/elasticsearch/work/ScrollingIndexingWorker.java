/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Delbosc Benoit
 */

package org.nuxeo.elasticsearch.work;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ScrollResult;
import org.nuxeo.ecm.core.work.api.Work;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

import java.util.Collections;
import java.util.List;

import static org.nuxeo.elasticsearch.ElasticSearchConstants.REINDEX_BUCKET_READ_PROPERTY;

/**
 * Worker to reindex a large amount of document
 *
 * @since 7.1
 */
public class ScrollingIndexingWorker extends BaseIndexingWorker implements Work {
    private static final Log log = LogFactory.getLog(ScrollingIndexingWorker.class);

    private static final long serialVersionUID = -4507677669419340384L;

    private static final String DEFAULT_BUCKET_SIZE = "500";

    private static final long WARN_DOC_COUNT = 500;

    protected final String nxql;

    protected transient WorkManager workManager;

    protected long documentCount = 0;

    public ScrollingIndexingWorker(String repositoryName, String nxql) {
        this.repositoryName = repositoryName;
        this.nxql = nxql;
    }

    @Override
    public String getTitle() {
        return "Elasticsearch scrolling indexer: " + nxql + ", processed " + documentCount;
    }

    @Override
    protected void doWork() {
        String jobName = getSchedulePath().getPath();
        if (log.isDebugEnabled()) {
            log.debug(String.format("Re-indexing job: %s started, NXQL: %s on repository: %s", jobName, nxql,
                    repositoryName));
        }
        openSystemSession();
        int bucketSize = getBucketSize();
        ScrollResult ret = session.scroll(nxql, bucketSize, 60);
        int bucketCount = 0;
        boolean warnAtEnd = false;
        try {
            while (ret.hasResults()) {
                documentCount += ret.getResultIds().size();
                scheduleBucketWorker(ret.getResultIds(), false);
                bucketCount += 1;
                ret = session.scroll(ret.getScrollId());
                TransactionHelper.commitOrRollbackTransaction();
                TransactionHelper.startTransaction();
            }
            if (documentCount > WARN_DOC_COUNT) {
                warnAtEnd = true;
                scheduleBucketWorker(Collections.emptyList(), warnAtEnd);
            }
        } finally {
            if (warnAtEnd || log.isDebugEnabled()) {
                String message = String.format("Re-indexing job: %s has submited %d documents in %d bucket workers",
                        jobName, documentCount, bucketCount);
                if (warnAtEnd) {
                    log.warn(message);
                } else {
                    log.debug(message);
                }
            }
        }
    }

    protected void scheduleBucketWorker(List<String> bucket, boolean isLast) {
        if (bucket.isEmpty()) {
            return;
        }
        BucketIndexingWorker subWorker = new BucketIndexingWorker(repositoryName, bucket, isLast);
        getWorkManager().schedule(subWorker);
    }

    protected WorkManager getWorkManager() {
        if (workManager == null) {
            workManager = Framework.getLocalService(WorkManager.class);
        }
        return workManager;
    }

    protected int getBucketSize() {
        String value = Framework.getProperty(REINDEX_BUCKET_READ_PROPERTY, DEFAULT_BUCKET_SIZE);
        return Integer.parseInt(value);
    }

}
