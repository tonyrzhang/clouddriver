/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.provider.view

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.clouddriver.google.GoogleCloudProvider
import com.netflix.spinnaker.clouddriver.google.cache.Keys
import com.netflix.spinnaker.clouddriver.google.model.GoogleApplication2
import com.netflix.spinnaker.clouddriver.model.ApplicationProvider
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

import static com.netflix.spinnaker.clouddriver.google.cache.Keys.Namespace.APPLICATIONS
import static com.netflix.spinnaker.clouddriver.google.cache.Keys.Namespace.CLUSTERS

@ConditionalOnProperty(value = "google.providerImpl", havingValue = "new")
@Component
class GoogleApplicationProvider implements ApplicationProvider {
  @Autowired
  Registry registry

  @Autowired
  AccountCredentialsProvider accountCredentialsProvider

  @Autowired
  Cache cacheView

  @Autowired
  ObjectMapper objectMapper
  
  @Override
  Set<GoogleApplication2.View> getApplications(boolean expand) {
    def filter = expand ? RelationshipCacheFilter.include(CLUSTERS.ns) : RelationshipCacheFilter.none()
    cacheView.getAll(APPLICATIONS.ns,
                     cacheView.filterIdentifiers(APPLICATIONS.ns, "$GoogleCloudProvider.GCE:*"),
                     filter).collect { applicationFromCacheData(it) } as Set
  }

  @Override
  GoogleApplication2.View getApplication(String name) {
    CacheData cacheData = cacheView.get(APPLICATIONS.ns,
                                        Keys.getApplicationKey(name),
                                        RelationshipCacheFilter.include(CLUSTERS.ns))
    if (cacheData) {
      return applicationFromCacheData(cacheData)
    }
  }

  GoogleApplication2.View applicationFromCacheData(CacheData cacheData) {
    GoogleApplication2.View applicationView = objectMapper.convertValue(cacheData.attributes, GoogleApplication2)?.view

    cacheData.relationships[CLUSTERS.ns].each { String clusterKey ->
      def clusterKeyParsed = Keys.parse(clusterKey)
      applicationView.clusterNames[clusterKeyParsed.account] << clusterKeyParsed.name
    }

    applicationView
  }
}
