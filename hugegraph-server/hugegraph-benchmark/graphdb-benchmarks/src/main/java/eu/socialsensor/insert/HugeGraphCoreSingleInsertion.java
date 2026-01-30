/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package eu.socialsensor.insert;

import java.io.File;

import org.apache.tinkerpop.gremlin.structure.Vertex;

import org.apache.hugegraph.HugeGraph;
import org.apache.tinkerpop.gremlin.structure.T;
import eu.socialsensor.graphdatabases.GraphDatabaseBase;
import eu.socialsensor.main.GraphDatabaseType;

public class HugeGraphCoreSingleInsertion extends InsertionBase<Vertex> {

    private final HugeGraph graph;

    public HugeGraphCoreSingleInsertion(HugeGraph graph, File resultPath) {
        super(GraphDatabaseType.HUGEGRAPH_CORE, resultPath);
        this.graph = graph;
    }

    @Override
    protected Vertex getOrCreate(String value) {
        Vertex vertex = null;
        if (value != null && !value.isEmpty()) {
            Integer id = Integer.valueOf(value);
            vertex = this.graph.vertices(id).next();
            if (vertex == null) {
                vertex = this.graph.addVertex(T.label, "node",
                                              GraphDatabaseBase.NODE_ID, id);
                this.graph.tx().commit();
            }
        }
        return vertex;
    }

    @Override
    protected void relateNodes(Vertex src, Vertex dest) {
        src.addEdge(GraphDatabaseBase.SIMILAR, dest);
        this.graph.tx().commit();
    }
}
