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
package org.apache.pinot.core.indexsegment.mutable;

import org.apache.pinot.core.data.GenericRow;
import org.apache.pinot.core.indexsegment.IndexSegment;
import org.apache.pinot.core.realtime.stream.StreamMessageMetadata;


public interface MutableSegment extends IndexSegment {

  /**
   * Indexes a record into the segment with optionally provided metadata.
   *
   * @param row Record represented as a {@link GenericRow}
   * @param msgMetadata the metadata associated with the message
   * @return Whether the segment is full (i.e. cannot index more record into it)
   */
   boolean index(GenericRow row, StreamMessageMetadata msgMetadata);

  /**
   * Returns the number of records already indexed into the segment.
   *
   * @return The number of records indexed
   */
  int getNumDocsIndexed();
}
