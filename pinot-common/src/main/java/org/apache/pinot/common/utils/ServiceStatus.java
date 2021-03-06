/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.common.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.LiveInstance;
import org.apache.pinot.common.config.TableNameBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utility class to obtain the status of the Pinot instance running in this JVM.
 */
@SuppressWarnings("unused")
public class ServiceStatus {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServiceStatus.class);

  public enum Status {
    STARTING, GOOD, BAD
  }

  public static final String STATUS_DESCRIPTION_NONE = "None";
  public static final String STATUS_DESCRIPTION_INIT = "Init";
  public static final String STATUS_DESCRIPTION_NO_HELIX_STATE = "Helix state does not exist";

  private static final int MAX_RESOURCE_NAMES_TO_LOG = 5;

  /**
   * Callback that returns the status of the service.
   */
  public interface ServiceStatusCallback {

    Status getServiceStatus();

    String getStatusDescription();
  }

  private static ServiceStatusCallback serviceStatusCallback = null;

  public static void setServiceStatusCallback(ServiceStatusCallback serviceStatusCallback) {
    ServiceStatus.serviceStatusCallback = serviceStatusCallback;
  }

  public static Status getServiceStatus() {
    if (serviceStatusCallback == null) {
      return Status.STARTING;
    } else {
      try {
        return serviceStatusCallback.getServiceStatus();
      } catch (Exception e) {
        LOGGER.warn("Caught exception while reading the service status", e);
        return Status.BAD;
      }
    }
  }

  public static String getStatusDescription() {
    if (serviceStatusCallback == null) {
      return STATUS_DESCRIPTION_INIT;
    } else {
      try {
        return serviceStatusCallback.getStatusDescription();
      } catch (Exception e) {
        return "Exception: " + e.getMessage();
      }
    }
  }

  public static class MultipleCallbackServiceStatusCallback implements ServiceStatusCallback {
    private final List<? extends ServiceStatusCallback> _statusCallbacks;

    public MultipleCallbackServiceStatusCallback(List<? extends ServiceStatusCallback> statusCallbacks) {
      _statusCallbacks = statusCallbacks;
    }

    @Override
    public Status getServiceStatus() {
      // Iterate through all callbacks, returning the first non GOOD one as the service status
      for (ServiceStatusCallback statusCallback : _statusCallbacks) {
        final Status serviceStatus = statusCallback.getServiceStatus();
        if (serviceStatus != Status.GOOD) {
          return serviceStatus;
        }
      }

      // All callbacks report good, therefore we're good too
      return Status.GOOD;
    }

    @Override
    public String getStatusDescription() {
      StringBuilder statusDescription = new StringBuilder();
      for (ServiceStatusCallback statusCallback : _statusCallbacks) {
        statusDescription.append(statusCallback.getClass().getSimpleName()).append(":")
            .append(statusCallback.getStatusDescription()).append(";");
      }
      return statusDescription.toString();
    }
  }

  /**
   * Service status callback that compares ideal state with another Helix state. Used to share most of the logic between
   * the ideal state/external view comparison and ideal state/current state comparison.
   */
  private static abstract class IdealStateMatchServiceStatusCallback<T> implements ServiceStatusCallback {
    protected final String _clusterName;
    protected final String _instanceName;
    protected final HelixAdmin _helixAdmin;
    protected final HelixDataAccessor _helixDataAccessor;

    private final Set<String> _resourcesToMonitor;
    private final int _numTotalResourcesToMonitor;
    private Iterator<String> _resourceIterator = null;
    // Minimum number of resources to be in converged state before we declare the service state as STARTED
    private final int _minResourcesStartCount;

    private String _statusDescription = STATUS_DESCRIPTION_INIT;

    public IdealStateMatchServiceStatusCallback(HelixManager helixManager, String clusterName, String instanceName,
        double minResourcesStartPercent) {
      _clusterName = clusterName;
      _instanceName = instanceName;
      _helixAdmin = helixManager.getClusterManagmentTool();
      _helixDataAccessor = helixManager.getHelixDataAccessor();

      _resourcesToMonitor = new HashSet<>();
      for (String resourceName : _helixAdmin.getResourcesInCluster(_clusterName)) {
        // Only monitor table resources and broker resource
        if (!TableNameBuilder.isTableResource(resourceName) && !resourceName
            .equals(CommonConstants.Helix.BROKER_RESOURCE_INSTANCE)) {
          continue;
        }
        // Only monitor enabled resources
        IdealState idealState = getResourceIdealState(resourceName);
        if (idealState.isEnabled()) {
          for (String partitionName : idealState.getPartitionSet()) {
            if (idealState.getInstanceSet(partitionName).contains(_instanceName)) {
              _resourcesToMonitor.add(resourceName);
              break;
            }
          }
        }
      }
      _numTotalResourcesToMonitor = _resourcesToMonitor.size();
      _minResourcesStartCount = (int) Math.ceil(minResourcesStartPercent * _numTotalResourcesToMonitor / 100);

      LOGGER.info("Monitoring {} resources: {} for start up of instance {}", _numTotalResourcesToMonitor,
          getResourceListAsString(), _instanceName);
    }

    public IdealStateMatchServiceStatusCallback(HelixManager helixManager, String clusterName, String instanceName,
        List<String> resourcesToMonitor, double minResourcesStartPercent) {
      _clusterName = clusterName;
      _instanceName = instanceName;
      _helixAdmin = helixManager.getClusterManagmentTool();
      _helixDataAccessor = helixManager.getHelixDataAccessor();

      _resourcesToMonitor = new HashSet<>(resourcesToMonitor);
      _numTotalResourcesToMonitor = _resourcesToMonitor.size();

      _minResourcesStartCount = (int) Math.ceil(minResourcesStartPercent * _numTotalResourcesToMonitor / 100);
      LOGGER.info("Monitoring {} resources: {} for start up of instance {}", _numTotalResourcesToMonitor,
          getResourceListAsString(), _instanceName);
    }

    protected abstract T getState(String resourceName);

    protected abstract Map<String, String> getPartitionStateMap(T state);

    protected abstract String getMatchName();

    private boolean isDone() {
      return _numTotalResourcesToMonitor - _resourcesToMonitor.size() >= _minResourcesStartCount;
    }

    // Each time getServiceStatus() is called, we move on to the next resource that needs to be examined. If we
    // reach the end, we set the iterator back to the beginning, starting again on the resource we left off
    // a while ago.
    // We do so until minResourcesStartPercent percent of resources have converged their ExternalView (or CurrentState)
    // to the IdealState. If any resource has not converged (and we have still not reached the threshold percent) then
    // we return immediately.
    // This allows us to move forward with resources that have converged as opposed to getting stuck with those that
    // have not.
    // In large installations with 1000s of resources, some resources may be stuck in transitions due to zookeeper
    // connection issues in helix. In such cases, setting a percentage threshold to be (say) 99.9% allows us to move
    // past and declare the server as having STARTED as opposed to waiting for the one resource that may never converge.
    // Note:
    //   - We still keep the number of zk access to a minimum, like before. Another method maybe to get all tables all
    //     the time, but that may increase the number of zk reads.
    //   - We may also need to keep track of how many partitions within a resource have converged, and track that
    //     percentage as well. For now, we keep the code simple(r), and revisit this if necessary.
    //   - It may be useful to consider a rewrite of this class where we expose the tables and partitions still
    //     pending, thus allowing an external system to make the decision on whether or not to declare the status
    //     as STARTING (perhaps depending on SLA for the resource, etc.).
    @Override
    public synchronized Status getServiceStatus() {

      while (!isDone()) {
        String resourceName;
        if (_resourceIterator == null || !_resourceIterator.hasNext()) {
          _resourceIterator = _resourcesToMonitor.iterator();
        }
        resourceName = _resourceIterator.next();
        StatusDescriptionPair statusDescriptionPair = evaluateResourceStatus(resourceName);

        if (statusDescriptionPair._status == Status.GOOD) {
          // Resource is done starting up, remove it from the set
          _resourceIterator.remove();
        } else {
          _statusDescription = String
              .format("%s, waitingFor=%s, resource=%s, numResourcesLeft=%d, numTotalResources=%d, minStartCount=%d,",
                  statusDescriptionPair._description, getMatchName(), resourceName, _resourcesToMonitor.size(),
                  _numTotalResourcesToMonitor, _minResourcesStartCount);

          return statusDescriptionPair._status;
        }
      }
      _resourceIterator = null;

      // At this point, one of the following conditions hold:
      // 1. We entered the loop above, and all the remaining resources ended up in GOOD state.
      //    In that case _resourcesToMonitor would be empty.
      // 2. We entered the loop above and cleared most of the remaining resources, but some small
      //    number are still not converged. In that case, we exited the loop because we have met
      //    the threshold of resources that need to be GOOD. We will then scan the remaining and
      //    print some details of the ones that are remaining (upto a limit of MAX_RESOURCE_NAMES_TO_LOG)
      //    and are still not in converged state. We walk through the remaining ones (and may clear
      //    mores resources from _resourcesToMonitor that are GOOD state)
      // 3. We did not execute the loop at all (the percentage threshold satisfied right away). We will do
      //    the same action as for (2) above.
      // In all three cases above, we need to return Status.GOOD

      int logCount = MAX_RESOURCE_NAMES_TO_LOG;
      Iterator<String> resourceIterator = _resourcesToMonitor.iterator();
      while (resourceIterator.hasNext()) {
        String resource = resourceIterator.next();
        StatusDescriptionPair statusDescriptionPair = evaluateResourceStatus(resource);
        if (statusDescriptionPair._status == Status.GOOD) {
          resourceIterator.remove();
        } else {
          if (logCount-- <= 0) {
            break;
          }
          LOGGER.info("Resource: {}, StatusDescription: {}", resource, statusDescriptionPair._description);
        }
      }
      if (_resourcesToMonitor.isEmpty()) {
        _statusDescription = STATUS_DESCRIPTION_NONE;
      } else {
        _statusDescription = String
            .format("waitingFor=%s, numResourcesLeft=%d, numTotalResources=%d, minStartCount=%d, resourceList=%s",
                getMatchName(), _resourcesToMonitor.size(), _numTotalResourcesToMonitor, _minResourcesStartCount,
                getResourceListAsString());
        LOGGER.info("Instance {} returning GOOD because {}", _instanceName, _statusDescription);
      }

      return Status.GOOD;
    }

    private StatusDescriptionPair evaluateResourceStatus(String resourceName) {
      IdealState idealState = getResourceIdealState(resourceName);
      // If the resource has been removed or disabled, ignore it
      if (idealState == null || !idealState.isEnabled()) {
        return new StatusDescriptionPair(Status.GOOD, STATUS_DESCRIPTION_NONE);
      }

      T helixState = getState(resourceName);
      if (helixState == null) {
        return new StatusDescriptionPair(Status.STARTING, STATUS_DESCRIPTION_NO_HELIX_STATE);
      }

      // Check that all partitions that are supposed to be in any state other than OFFLINE have the same status in the
      // external view or went to ERROR state (which means that we tried to load the segments/resources but failed for
      // some reason)
      Map<String, String> partitionStateMap = getPartitionStateMap(helixState);
      for (String partitionName : idealState.getPartitionSet()) {
        String idealStateStatus = idealState.getInstanceStateMap(partitionName).get(_instanceName);

        // Skip this partition if it is not assigned to this instance or if the instance should be offline
        if (idealStateStatus == null || "OFFLINE".equals(idealStateStatus)) {
          continue;
        }

        // If the instance state is not ERROR and is not the same as what's expected from the ideal state, then it
        // hasn't finished starting up
        String currentStateStatus = partitionStateMap.get(partitionName);
        if (!idealStateStatus.equals(currentStateStatus)) {
          if ("ERROR".equals(currentStateStatus)) {
            LOGGER.error(String.format("Resource: %s, partition: %s is in ERROR state", resourceName, partitionName));
          } else {
            String description = String
                .format("partition=%s, expected=%s, found=%s", partitionName, idealStateStatus, currentStateStatus);
            return new StatusDescriptionPair(Status.STARTING, description);
          }
        }
      }
      return new StatusDescriptionPair(Status.GOOD, STATUS_DESCRIPTION_NONE);
    }

    private String getResourceListAsString() {
      if (_resourcesToMonitor.size() <= MAX_RESOURCE_NAMES_TO_LOG) {
        return _resourcesToMonitor.toString();
      }
      StringBuilder stringBuilder = new StringBuilder("[");
      Iterator<String> resourceIterator = _resourcesToMonitor.iterator();
      for (int i = 0; i < MAX_RESOURCE_NAMES_TO_LOG; i++) {
        stringBuilder.append(resourceIterator.next()).append(", ");
      }
      return stringBuilder.append("...]").toString();
    }

    @Override
    public synchronized String getStatusDescription() {
      return _statusDescription;
    }

    protected IdealState getResourceIdealState(String resourceName) {
      return _helixAdmin.getResourceIdealState(_clusterName, resourceName);
    }
  }

  /**
   * Service status callback that reports starting until all resources relevant to this instance have a matching
   * external view and current state. This callback considers the ERROR state in the current view to be equivalent to
   * the ideal state value.
   */
  public static class IdealStateAndCurrentStateMatchServiceStatusCallback extends IdealStateMatchServiceStatusCallback<CurrentState> {
    private static final String MATCH_NAME = "CurrentStateMatch";

    public IdealStateAndCurrentStateMatchServiceStatusCallback(HelixManager helixManager, String clusterName,
        String instanceName, double minResourcesStartPercent) {
      super(helixManager, clusterName, instanceName, minResourcesStartPercent);
    }

    public IdealStateAndCurrentStateMatchServiceStatusCallback(HelixManager helixManager, String clusterName,
        String instanceName, List<String> resourcesToMonitor, double minResourcesStartPercent) {
      super(helixManager, clusterName, instanceName, resourcesToMonitor, minResourcesStartPercent);
    }

    @Override
    protected CurrentState getState(String resourceName) {
      PropertyKey.Builder keyBuilder = _helixDataAccessor.keyBuilder();
      LiveInstance liveInstance = _helixDataAccessor.getProperty(keyBuilder.liveInstance(_instanceName));
      String sessionId = liveInstance.getSessionId();
      return _helixDataAccessor.getProperty(keyBuilder.currentState(_instanceName, sessionId, resourceName));
    }

    @Override
    protected Map<String, String> getPartitionStateMap(CurrentState state) {
      return state.getPartitionStateMap();
    }

    @Override
    protected String getMatchName() {
      return MATCH_NAME;
    }
  }

  /**
   * Service status callback that reports starting until all resources relevant to this instance have a matching
   * external view and ideal state. This callback considers the ERROR state in the external view to be equivalent to the
   * ideal state value.
   */
  public static class IdealStateAndExternalViewMatchServiceStatusCallback extends IdealStateMatchServiceStatusCallback<ExternalView> {
    private static final String MATCH_NAME = "ExternalViewMatch";

    public IdealStateAndExternalViewMatchServiceStatusCallback(HelixManager helixManager, String clusterName,
        String instanceName, double minResourcesStartPercent) {
      super(helixManager, clusterName, instanceName, minResourcesStartPercent);
    }

    public IdealStateAndExternalViewMatchServiceStatusCallback(HelixManager helixManager, String clusterName,
        String instanceName, List<String> resourcesToMonitor, double minResourcesStartPercent) {
      super(helixManager, clusterName, instanceName, resourcesToMonitor, minResourcesStartPercent);
    }

    @Override
    protected ExternalView getState(String resourceName) {
      return _helixAdmin.getResourceExternalView(_clusterName, resourceName);
    }

    @Override
    protected Map<String, String> getPartitionStateMap(ExternalView state) {
      Map<String, String> partitionState = new HashMap<>();

      for (String partition : state.getPartitionSet()) {
        Map<String, String> instanceStateMap = state.getStateMap(partition);
        if (instanceStateMap.containsKey(_instanceName)) {
          partitionState.put(partition, instanceStateMap.get(_instanceName));
        }
      }

      return partitionState;
    }

    @Override
    protected String getMatchName() {
      return MATCH_NAME;
    }
  }

  private static class StatusDescriptionPair {
    Status _status;
    String _description;

    StatusDescriptionPair(Status status, String description) {
      _status = status;
      _description = description;
    }
  }
}
