/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search;

import java.util.Objects;

/** A clause in a BooleanQuery. */
public record BooleanClause(Query query, Occur occur) {

  /** Specifies how clauses are to occur in matching documents. */
  public enum Occur {

    /** Use this operator for clauses that <i>must</i> appear in the matching documents. */
    MUST {
      @Override
      public String toString() {
        return "+";
      }
    },

    /** Like {@link #MUST} except that these clauses do not participate in scoring. */
    FILTER {
      @Override
      public String toString() {
        return "#";
      }
    },

    /**
     * Use this operator for clauses that <i>should</i> appear in the matching documents. For a
     * BooleanQuery with no <code>MUST</code> clauses one or more <code>SHOULD</code> clauses must
     * match a document for the BooleanQuery to match.
     *
     * @see BooleanQuery.Builder#setMinimumNumberShouldMatch
     */
    SHOULD {
      @Override
      public String toString() {
        return "";
      }
    },

    /**
     * Use this operator for clauses that <i>must not</i> appear in the matching documents. Note
     * that it is not possible to search for queries that only consist of a <code>MUST_NOT</code>
     * clause. These clauses do not contribute to the score of documents.
     */
    MUST_NOT {
      @Override
      public String toString() {
        return "-";
      }
    };
  }

  /** Constructs a BooleanClause. */
  public BooleanClause {
    Objects.requireNonNull(query, "Query must not be null");
    Objects.requireNonNull(occur, "Occur must not be null");
  }

  public boolean isProhibited() {
    return Occur.MUST_NOT == occur;
  }

  public boolean isRequired() {
    return occur == Occur.MUST || occur == Occur.FILTER;
  }

  public boolean isScoring() {
    return occur == Occur.MUST || occur == Occur.SHOULD;
  }
}
