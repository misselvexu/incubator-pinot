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
package org.apache.pinot.pql.parsers.pql2.ast;

import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.common.request.Selection;
import org.apache.pinot.pql.parsers.Pql2CompilationException;


/**
 * AST node for an output column.
 */
public class OutputColumnAstNode extends BaseAstNode {
  @Override
  public void updateBrokerRequest(BrokerRequest brokerRequest) {
    for (AstNode astNode : getChildren()) {
      // If the column is a function call, it must be an aggregation function
      if (astNode instanceof FunctionCallAstNode) {
        FunctionCallAstNode node = (FunctionCallAstNode) astNode;
        brokerRequest.addToAggregationsInfo(node.buildAggregationInfo());
      } else if (astNode instanceof IdentifierAstNode) {
        Selection selection = brokerRequest.getSelections();
        if (selection == null) {
          selection = new Selection();
          brokerRequest.setSelections(selection);
        }

        IdentifierAstNode node = (IdentifierAstNode) astNode;
        selection.addToSelectionColumns(node.getName());
      } else {
        throw new Pql2CompilationException("Output column is neither a function nor an identifier");
      }
    }
  }
}
