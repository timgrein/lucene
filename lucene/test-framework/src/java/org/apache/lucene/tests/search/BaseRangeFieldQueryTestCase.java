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
package org.apache.lucene.tests.search;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;

/**
 * Abstract class to do basic tests for a RangeField query. Testing rigor inspired by {@code
 * BaseGeoPointTestCase}
 */
public abstract class BaseRangeFieldQueryTestCase extends LuceneTestCase {
  protected abstract Field newRangeField(Range box);

  protected abstract Query newIntersectsQuery(Range box);

  protected abstract Query newContainsQuery(Range box);

  protected abstract Query newWithinQuery(Range box);

  protected abstract Query newCrossesQuery(Range box);

  protected abstract Range nextRange(int dimensions) throws Exception;

  protected int dimension() {
    return random().nextInt(4) + 1;
  }

  public void testRandomTiny() throws Exception {
    // Make sure single-leaf-node case is OK:
    for (int i = 0; i < 10; ++i) {
      doTestRandom(10, false);
    }
  }

  public void testRandomMedium() throws Exception {
    doTestRandom(1000, false);
  }

  @Nightly
  public void testRandomBig() throws Exception {
    doTestRandom(200000, false);
  }

  public void testMultiValued() throws Exception {
    doTestRandom(1000, true);
  }

  public void testAllEqual() throws Exception {
    int numDocs = atLeast(1000);
    int dimensions = dimension();
    Range[][] ranges = new Range[numDocs][];
    Range[] theRange = new Range[] {nextRange(dimensions)};
    Arrays.fill(ranges, theRange);
    verify(ranges);
  }

  // Force low cardinality leaves
  public void testLowCardinality() throws Exception {
    int numDocs = atLeast(1000);
    int dimensions = dimension();

    int cardinality = TestUtil.nextInt(random(), 2, 20);
    Range[][] diffRanges = new Range[cardinality][];
    for (int i = 0; i < cardinality; i++) {
      diffRanges[i] = new Range[] {nextRange(dimensions)};
    }

    Range[][] ranges = new Range[numDocs][];
    for (int i = 0; i < numDocs; i++) {
      ranges[i] = diffRanges[random().nextInt(cardinality)];
    }
    verify(ranges);
  }

  private void doTestRandom(int count, boolean multiValued) throws Exception {
    int numDocs = atLeast(count);
    int dimensions = dimension();

    if (VERBOSE) {
      System.out.println("TEST: numDocs=" + numDocs);
    }

    Range[][] ranges = new Range[numDocs][];

    boolean haveRealDoc = true;

    nextdoc:
    for (int id = 0; id < numDocs; ++id) {
      int x = random().nextInt(20);
      if (ranges[id] == null) {
        ranges[id] = new Range[] {nextRange(dimensions)};
      }
      if (x == 17) {
        // some docs don't have a box:
        ranges[id][0].isMissing = true;
        if (VERBOSE) {
          System.out.println("  id=" + id + " is missing");
        }
        continue;
      }

      if (multiValued == true && random().nextBoolean()) {
        // randomly add multi valued documents (up to 2 fields)
        int n = random().nextInt(2) + 1;
        ranges[id] = new Range[n];
        for (int i = 0; i < n; ++i) {
          ranges[id][i] = nextRange(dimensions);
        }
      }

      if (id > 0 && x < 9 && haveRealDoc) {
        int oldID;
        int i = 0;
        // don't step on missing ranges:
        while (true) {
          oldID = random().nextInt(id);
          if (ranges[oldID][0].isMissing == false) {
            break;
          } else if (++i > id) {
            continue nextdoc;
          }
        }

        if (x == dimensions * 2) {
          // Fully identical box (use first box in case current is multivalued but old is not)
          for (int d = 0; d < dimensions; ++d) {
            ranges[id][0].setMin(d, ranges[oldID][0].getMin(d));
            ranges[id][0].setMax(d, ranges[oldID][0].getMax(d));
          }
          if (VERBOSE) {
            System.out.println(
                "  id="
                    + id
                    + " box="
                    + Arrays.toString(ranges[id])
                    + " (same box as doc="
                    + oldID
                    + ")");
          }
        } else {
          for (int m = 0, even = dimensions % 2; m < dimensions * 2; ++m) {
            if (x == m) {
              int d = (int) Math.floor(m / 2f);
              // current could be multivalue but old may not be, so use first box
              if (even == 0) { // even is min
                ranges[id][0].setMin(d, ranges[oldID][0].getMin(d));
                if (VERBOSE) {
                  System.out.println(
                      "  id="
                          + id
                          + " box="
                          + Arrays.toString(ranges[id])
                          + " (same min["
                          + d
                          + "] as doc="
                          + oldID
                          + ")");
                }
              } else { // odd is max
                ranges[id][0].setMax(d, ranges[oldID][0].getMax(d));
                if (VERBOSE) {
                  System.out.println(
                      "  id="
                          + id
                          + " box="
                          + Arrays.toString(ranges[id])
                          + " (same max["
                          + d
                          + "] as doc="
                          + oldID
                          + ")");
                }
              }
            }
          }
        }
      }
    }
    verify(ranges);
  }

  private void verify(Range[][] ranges) throws Exception {
    IndexWriterConfig iwc = newIndexWriterConfig();
    // Else seeds may not reproduce:
    iwc.setMergeScheduler(new SerialMergeScheduler());
    // Else we can get O(N^2) merging
    int mbd = iwc.getMaxBufferedDocs();
    if (mbd != -1 && mbd < ranges.length / 100) {
      iwc.setMaxBufferedDocs(ranges.length / 100);
    }
    Directory dir;
    if (ranges.length > 50000) {
      // Avoid slow codecs like SimpleText
      iwc.setCodec(TestUtil.getDefaultCodec());
      dir = newFSDirectory(createTempDir(getClass().getSimpleName()));
    } else {
      dir = newDirectory();
    }

    Set<Integer> deleted = new HashSet<>();
    IndexWriter w = new IndexWriter(dir, iwc);
    for (int id = 0; id < ranges.length; ++id) {
      Document doc = new Document();
      doc.add(new StringField("id", "" + id, Field.Store.NO));
      doc.add(new NumericDocValuesField("id", id));
      if (ranges[id][0].isMissing == false) {
        for (int n = 0; n < ranges[id].length; ++n) {
          addRange(doc, ranges[id][n]);
        }
      }
      w.addDocument(doc);
      if (id > 0 && random().nextInt(100) == 1) {
        int idToDelete = random().nextInt(id);
        w.deleteDocuments(new Term("id", "" + idToDelete));
        deleted.add(idToDelete);
        if (VERBOSE) {
          System.out.println("  delete id=" + idToDelete);
        }
      }
    }

    if (random().nextBoolean()) {
      w.forceMerge(1);
    }
    final IndexReader r = DirectoryReader.open(w);
    w.close();
    IndexSearcher s = newSearcher(r);

    int dimensions = ranges[0][0].numDimensions();
    int iters = atLeast(25);
    Bits liveDocs = MultiBits.getLiveDocs(s.getIndexReader());
    int maxDoc = s.getIndexReader().maxDoc();

    for (int iter = 0; iter < iters; ++iter) {
      if (VERBOSE) {
        System.out.println("\nTEST: iter=" + iter + " s=" + s);
      }

      // occasionally test open ended bounding ranges
      Range queryRange = nextRange(dimensions);
      int rv = random().nextInt(4);
      Query query;
      Range.QueryType queryType;
      if (rv == 0) {
        queryType = Range.QueryType.INTERSECTS;
        query = newIntersectsQuery(queryRange);
      } else if (rv == 1) {
        queryType = Range.QueryType.CONTAINS;
        query = newContainsQuery(queryRange);
      } else if (rv == 2) {
        queryType = Range.QueryType.WITHIN;
        query = newWithinQuery(queryRange);
      } else {
        queryType = Range.QueryType.CROSSES;
        query = newCrossesQuery(queryRange);
      }

      if (VERBOSE) {
        System.out.println("  query=" + query);
      }

      final FixedBitSet hits = s.search(query, FixedBitSetCollector.createManager(maxDoc));

      NumericDocValues docIDToID = MultiDocValues.getNumericValues(r, "id");
      for (int docID = 0; docID < maxDoc; ++docID) {
        assertEquals(docID, docIDToID.nextDoc());
        int id = (int) docIDToID.longValue();
        boolean expected;
        boolean isDeleted = liveDocs != null && liveDocs.get(docID) == false;
        boolean isMissing = ranges[id][0].isMissing;

        if (isDeleted) {
          expected = false;
        } else if (isMissing) {
          expected = false;
        } else {
          expected = expectedResult(queryRange, ranges[id], queryType);
        }

        if (hits.get(docID) != expected) {
          StringBuilder b = new StringBuilder();
          b.append("FAIL (iter ").append(iter).append("): ");
          if (expected == true) {
            b.append("id=")
                .append(id)
                .append(ranges[id].length > 1 ? " (MultiValue) " : " ")
                .append("should match but did not\n");
          } else {
            b.append("id=").append(id).append(" should not match but did\n");
          }
          b.append(" queryRange=").append(queryRange).append("\n");
          b.append(" box").append((ranges[id].length > 1) ? "es=" : "=").append(ranges[id][0]);
          for (int n = 1; n < ranges[id].length; ++n) {
            b.append(", ");
            b.append(ranges[id][n]);
          }
          b.append("\n queryType=").append(queryType).append("\n");
          b.append(" docDeleted?=").append(isDeleted).append("\n");
          b.append(" rangeMissing?=").append(isMissing);
          fail("wrong hit (first of possibly more):\n\n" + b);
        }
      }
    }
    IOUtils.close(r, dir);
  }

  protected void addRange(Document doc, Range box) {
    doc.add(newRangeField(box));
  }

  protected boolean expectedResult(Range queryRange, Range[] range, Range.QueryType queryType) {
    for (int i = 0; i < range.length; ++i) {
      if (expectedBBoxQueryResult(queryRange, range[i], queryType) == true) {
        return true;
      }
    }
    return false;
  }

  protected boolean expectedBBoxQueryResult(
      Range queryRange, Range range, Range.QueryType queryType) {
    if (queryRange.isEqual(range) && queryType != Range.QueryType.CROSSES) {
      return true;
    }
    Range.QueryType relation = range.relate(queryRange);
    if (queryType == Range.QueryType.INTERSECTS) {
      return relation != null;
    } else if (queryType == Range.QueryType.CROSSES) {
      // by definition, RangeFields that CONTAIN the query are also considered to cross
      return relation == queryType || relation == Range.QueryType.CONTAINS;
    }
    return relation == queryType;
  }

  /** base class for range verification */
  protected abstract static class Range {
    protected boolean isMissing = false;

    /** supported query relations */
    protected enum QueryType {
      INTERSECTS,
      WITHIN,
      CONTAINS,
      CROSSES
    }

    protected abstract int numDimensions();

    protected abstract Object getMin(int dim);

    protected abstract void setMin(int dim, Object val);

    protected abstract Object getMax(int dim);

    protected abstract void setMax(int dim, Object val);

    protected abstract boolean isEqual(Range other);

    protected abstract boolean isDisjoint(Range other);

    protected abstract boolean isWithin(Range other);

    protected abstract boolean contains(Range other);

    protected QueryType relate(Range other) {
      if (isDisjoint(other)) {
        // if disjoint; return null:
        return null;
      } else if (isWithin(other)) {
        return QueryType.WITHIN;
      } else if (contains(other)) {
        return QueryType.CONTAINS;
      }
      return QueryType.CROSSES;
    }
  }
}
