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

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

public class TestBooleanRewrites extends LuceneTestCase {

  public void testOneClauseRewriteOptimization() throws Exception {
    final String FIELD = "content";
    final String VALUE = "foo";

    Directory dir = newDirectory();
    (new RandomIndexWriter(random(), dir)).close();
    IndexReader r = DirectoryReader.open(dir);

    TermQuery expected = new TermQuery(new Term(FIELD, VALUE));

    final int numLayers = atLeast(3);
    Query actual = new TermQuery(new Term(FIELD, VALUE));

    for (int i = 0; i < numLayers; i++) {

      BooleanQuery.Builder bq = new BooleanQuery.Builder();
      bq.add(
          actual, random().nextBoolean() ? BooleanClause.Occur.SHOULD : BooleanClause.Occur.MUST);
      actual = bq.build();
    }

    assertEquals(
        numLayers + ": " + actual.toString(), expected, new IndexSearcher(r).rewrite(actual));

    r.close();
    dir.close();
  }

  public void testSingleFilterClause() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    Field f = newTextField("field", "a", Field.Store.NO);
    doc.add(f);
    w.addDocument(doc);
    w.commit();

    DirectoryReader reader = w.getReader();
    final IndexSearcher searcher = new IndexSearcher(reader);

    BooleanQuery.Builder query1 = new BooleanQuery.Builder();
    query1.add(new TermQuery(new Term("field", "a")), Occur.FILTER);

    // Single clauses rewrite to a term query
    final Query rewritten1 = query1.build().rewrite(searcher);
    assertTrue(rewritten1 instanceof BoostQuery);
    assertEquals(0f, ((BoostQuery) rewritten1).getBoost(), 0f);

    // When there are two clauses, we cannot rewrite, but if one of them creates
    // a null scorer we will end up with a single filter scorer and will need to
    // make sure to set score=0
    BooleanQuery.Builder query2 = new BooleanQuery.Builder();
    query2.add(new TermQuery(new Term("field", "a")), Occur.FILTER);
    query2.add(new TermQuery(new Term("missing_field", "b")), Occur.SHOULD);
    final Weight weight =
        searcher.createWeight(searcher.rewrite(query2.build()), ScoreMode.COMPLETE, 1);
    final Scorer scorer = weight.scorer(reader.leaves().get(0));
    assertEquals(0, scorer.iterator().nextDoc());
    assertTrue(scorer.getClass().getName(), scorer instanceof FilterScorer);
    assertEquals(0f, scorer.score(), 0f);

    reader.close();
    w.close();
    dir.close();
  }

  public void testSingleMustMatchAll() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .build();
    assertEquals(
        new ConstantScoreQuery(new TermQuery(new Term("foo", "bar"))), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new BoostQuery(new MatchAllDocsQuery(), 42), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .build();
    assertEquals(
        new BoostQuery(new ConstantScoreQuery(new TermQuery(new Term("foo", "bar"))), 42),
        searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .build();
    assertEquals(new MatchAllDocsQuery(), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new BoostQuery(new MatchAllDocsQuery(), 42), Occur.MUST)
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .build();
    assertEquals(new BoostQuery(new MatchAllDocsQuery(), 42), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST_NOT)
            .build();
    assertEquals(bq, searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .build();
    assertEquals(new MatchAllDocsQuery(), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    Query expected =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    assertEquals(new ConstantScoreQuery(expected), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST_NOT)
            .build();
    expected =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST_NOT)
            .build();
    assertEquals(new ConstantScoreQuery(expected), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .build();
    assertEquals(bq, searcher.rewrite(bq));
  }

  public void testSingleMustMatchAllWithShouldClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .build();
    BooleanQuery expected =
        new BooleanQuery.Builder()
            .add(new ConstantScoreQuery(new TermQuery(new Term("foo", "bar"))), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .build();
    assertEquals(expected, searcher.rewrite(bq));
  }

  public void testDeduplicateMustAndFilter() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .build();
    assertEquals(new TermQuery(new Term("foo", "bar")), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    BooleanQuery expected =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    assertEquals(expected, searcher.rewrite(bq));
  }

  // Duplicate Should and Filter query is converted to Must (with minShouldMatch -1)
  public void testConvertShouldAndFilterToMust() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    // no minShouldMatch
    BooleanQuery bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .build();
    assertEquals(new TermQuery(new Term("foo", "bar")), searcher.rewrite(bq));

    // minShouldMatch is set to -1
    bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quz")), Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();

    BooleanQuery expected =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quz")), Occur.SHOULD)
            .setMinimumNumberShouldMatch(1)
            .build();
    assertEquals(expected, searcher.rewrite(bq));
  }

  // Duplicate Must or Filter with MustNot returns no match
  public void testDuplicateMustOrFilterWithMustNot() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    // Test Must with MustNot
    BooleanQuery bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            // other terms
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bad")), Occur.SHOULD)
            //
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST_NOT)
            .build();

    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(bq));

    // Test Filter with MustNot
    BooleanQuery bq2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            // other terms
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bad")), Occur.SHOULD)
            //
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST_NOT)
            .build();

    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(bq2));
  }

  // MatchAllQuery as MUST_NOT clause cannot return anything
  public void testMatchAllMustNot() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    // Test Must with MatchAll MustNot
    BooleanQuery bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "bad")), Occur.SHOULD)
            //
            .add(new MatchAllDocsQuery(), Occur.MUST_NOT)
            .build();

    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(bq));

    // Test Must with MatchAll MustNot and other MustNot
    BooleanQuery bq2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "bad")), Occur.SHOULD)
            //
            .add(new TermQuery(new Term("foo", "bor")), Occur.MUST_NOT)
            .add(new MatchAllDocsQuery(), Occur.MUST_NOT)
            .build();

    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(bq2));
  }

  public void testDeeplyNestedBooleanRewriteShouldClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());
    Function<Integer, TermQuery> termQueryFunction =
        (i) -> new TermQuery(new Term("layer[" + i + "]", "foo"));
    int depth = TestUtil.nextInt(random(), 10, 30);
    TestRewriteQuery rewriteQueryExpected = new TestRewriteQuery();
    TestRewriteQuery rewriteQuery = new TestRewriteQuery();
    BooleanQuery.Builder expectedQueryBuilder =
        new BooleanQuery.Builder().add(rewriteQueryExpected, Occur.FILTER);
    Query deepBuilder =
        new BooleanQuery.Builder()
            .add(rewriteQuery, Occur.SHOULD)
            .setMinimumNumberShouldMatch(1)
            .build();
    for (int i = depth; i > 0; i--) {
      TermQuery tq = termQueryFunction.apply(i);
      BooleanQuery.Builder bq =
          new BooleanQuery.Builder()
              .setMinimumNumberShouldMatch(2)
              .add(tq, Occur.SHOULD)
              .add(deepBuilder, Occur.SHOULD);
      deepBuilder = bq.build();
      expectedQueryBuilder.add(tq, Occur.FILTER);
      if (i == depth) {
        expectedQueryBuilder.add(rewriteQuery, Occur.FILTER);
      }
    }
    BooleanQuery bq = new BooleanQuery.Builder().add(deepBuilder, Occur.FILTER).build();
    Query expectedQuery =
        new BoostQuery(new ConstantScoreQuery(expectedQueryBuilder.build()), 0.0f);
    Query rewritten = searcher.rewrite(bq);
    assertEquals(expectedQuery, rewritten);
    // the SHOULD clauses cause more rewrites because they incrementally change to `MUST` and then
    // `FILTER`, plus the flattening of required clauses
    assertEquals("Depth=" + depth, depth * 2, rewriteQuery.numRewrites);
  }

  public void testDeeplyNestedBooleanRewrite() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());
    Function<Integer, TermQuery> termQueryFunction =
        (i) -> new TermQuery(new Term("layer[" + i + "]", "foo"));
    int depth = TestUtil.nextInt(random(), 10, 30);
    TestRewriteQuery rewriteQueryExpected = new TestRewriteQuery();
    TestRewriteQuery rewriteQuery = new TestRewriteQuery();
    BooleanQuery.Builder expectedQueryBuilder =
        new BooleanQuery.Builder().add(rewriteQueryExpected, Occur.FILTER);
    Query deepBuilder = new BooleanQuery.Builder().add(rewriteQuery, Occur.MUST).build();
    for (int i = depth; i > 0; i--) {
      TermQuery tq = termQueryFunction.apply(i);
      BooleanQuery.Builder bq =
          new BooleanQuery.Builder().add(tq, Occur.MUST).add(deepBuilder, Occur.MUST);
      deepBuilder = bq.build();
      expectedQueryBuilder.add(tq, Occur.FILTER);
      if (i == depth) {
        expectedQueryBuilder.add(rewriteQuery, Occur.FILTER);
      }
    }
    BooleanQuery bq = new BooleanQuery.Builder().add(deepBuilder, Occur.FILTER).build();
    Query expectedQuery =
        new BoostQuery(new ConstantScoreQuery(expectedQueryBuilder.build()), 0.0f);
    Query rewritten = searcher.rewrite(bq);
    assertEquals(expectedQuery, rewritten);
    // `depth` rewrites because of the flattening
    assertEquals("Depth=" + depth, depth, rewriteQuery.numRewrites);
  }

  public void testRemoveMatchAllFilter() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .build();
    assertEquals(new TermQuery(new Term("foo", "bar")), searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .build();
    Query expected =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    assertEquals(expected, searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .build();
    expected = new BoostQuery(new ConstantScoreQuery(new TermQuery(new Term("foo", "bar"))), 0.0f);
    assertEquals(expected, searcher.rewrite(bq));

    bq =
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .build();
    expected = new BoostQuery(new ConstantScoreQuery(new MatchAllDocsQuery()), 0.0f);
    assertEquals(expected, searcher.rewrite(bq));
  }

  public void testRandom() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    TextField f = new TextField("body", "a b c", Store.NO);
    doc.add(f);
    w.addDocument(doc);
    f.setStringValue("");
    w.addDocument(doc);
    f.setStringValue("a b");
    w.addDocument(doc);
    f.setStringValue("b c");
    w.addDocument(doc);
    f.setStringValue("a");
    w.addDocument(doc);
    f.setStringValue("c");
    w.addDocument(doc);
    final int numRandomDocs = atLeast(3);
    for (int i = 0; i < numRandomDocs; ++i) {
      final int numTerms = random().nextInt(20);
      StringBuilder text = new StringBuilder();
      for (int j = 0; j < numTerms; ++j) {
        text.append((char) ('a' + random().nextInt(4))).append(' ');
      }
      f.setStringValue(text.toString());
      w.addDocument(doc);
    }
    final IndexReader reader = w.getReader();
    w.close();
    final IndexSearcher searcher1 = newSearcher(reader);
    final IndexSearcher searcher2 =
        new IndexSearcher(reader) {
          @Override
          public Query rewrite(Query original) throws IOException {
            // no-op: disable rewriting
            return original;
          }
        };
    searcher2.setSimilarity(searcher1.getSimilarity());

    final int iters = atLeast(1000);
    for (int i = 0; i < iters; ++i) {
      Query query = randomBooleanQuery(random());
      final TopDocs td1 = searcher1.search(query, 100);
      final TopDocs td2 = searcher2.search(query, 100);
      try {
        assertEquals(td1, td2);
      } catch (AssertionError e) {
        System.out.println(query);
        Query rewritten = query;
        do {
          query = rewritten;
          rewritten = query.rewrite(searcher1);
          System.out.println(rewritten);
          TopDocs tdx = searcher2.search(rewritten, 100);
          if (td2.totalHits.value() != tdx.totalHits.value()) {
            System.out.println("Bad");
          }
        } while (query != rewritten);
        throw e;
      }
    }

    searcher1.getIndexReader().close();
    dir.close();
  }

  private Query randomBooleanQuery(Random random) {
    final int numClauses = random.nextInt(5);
    BooleanQuery.Builder b = new BooleanQuery.Builder();
    int numShoulds = 0;
    for (int i = 0; i < numClauses; ++i) {
      final Occur occur = Occur.values()[random.nextInt(Occur.values().length)];
      if (occur == Occur.SHOULD) {
        numShoulds++;
      }
      final Query query = randomQuery(random);
      b.add(query, occur);
    }
    b.setMinimumNumberShouldMatch(
        random().nextBoolean() ? 0 : TestUtil.nextInt(random(), 0, numShoulds + 1));
    Query query = b.build();
    if (random.nextBoolean()) {
      query = randomWrapper(random, query);
    }
    return query;
  }

  private Query randomWrapper(Random random, Query query) {
    switch (random.nextInt(2)) {
      case 0:
        return new BoostQuery(query, TestUtil.nextInt(random, 0, 4));
      case 1:
        return new ConstantScoreQuery(query);
      default:
        throw new AssertionError();
    }
  }

  private Query randomQuery(Random random) {
    if (random.nextInt(5) == 0) {
      return randomWrapper(random, randomQuery(random));
    }
    switch (random().nextInt(6)) {
      case 0:
        return new MatchAllDocsQuery();
      case 1:
        return new TermQuery(new Term("body", "a"));
      case 2:
        return new TermQuery(new Term("body", "b"));
      case 3:
        return new TermQuery(new Term("body", "c"));
      case 4:
        return new TermQuery(new Term("body", "d"));
      case 5:
        return randomBooleanQuery(random);
      default:
        throw new AssertionError();
    }
  }

  private void assertEquals(TopDocs td1, TopDocs td2) {
    assertEquals(td1.totalHits.value(), td2.totalHits.value());
    assertEquals(td1.scoreDocs.length, td2.scoreDocs.length);
    Map<Integer, Float> expectedScores =
        Arrays.stream(td1.scoreDocs).collect(Collectors.toMap(sd -> sd.doc, sd -> sd.score));
    Set<Integer> actualResultSet =
        Arrays.stream(td2.scoreDocs).map(sd -> sd.doc).collect(Collectors.toSet());

    assertEquals("Set of matching documents differs", expectedScores.keySet(), actualResultSet);

    for (ScoreDoc scoreDoc : td2.scoreDocs) {
      final float expectedScore = expectedScores.get(scoreDoc.doc);
      final float actualScore = scoreDoc.score;
      assertEquals(expectedScore, actualScore, expectedScore / 100); // error under 1%
    }
  }

  public void testDeduplicateShouldClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    Query query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .build();
    Query expected = new BoostQuery(new TermQuery(new Term("foo", "bar")), 2);
    assertEquals(expected, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new BoostQuery(new TermQuery(new Term("foo", "bar")), 2), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .build();
    expected =
        new BooleanQuery.Builder()
            .add(new BoostQuery(new TermQuery(new Term("foo", "bar")), 3), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .build();
    assertEquals(expected, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(2)
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .build();
    expected = query;
    assertEquals(expected, searcher.rewrite(query));
  }

  public void testDeduplicateMustClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    Query query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .build();
    Query expected = new BoostQuery(new TermQuery(new Term("foo", "bar")), 2);
    assertEquals(expected, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new BoostQuery(new TermQuery(new Term("foo", "bar")), 2), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST)
            .build();
    expected =
        new BooleanQuery.Builder()
            .add(new BoostQuery(new TermQuery(new Term("foo", "bar")), 3), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST)
            .build();
    assertEquals(expected, searcher.rewrite(query));
  }

  public void testFlattenInnerDisjunctions() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    Query inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .build();
    Query query =
        new BooleanQuery.Builder()
            .add(inner, Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .build();
    Query expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(0)
            .add(inner, Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(0)
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(1)
            .add(inner, Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(1)
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(2)
            .add(inner, Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));

    inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();
    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .build();
    assertSame(query, searcher.rewrite(query));
  }

  public void testFlattenInnerConjunctions() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    Query inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST)
            .build();
    Query query =
        new BooleanQuery.Builder()
            .add(inner, Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    Query expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(0)
            .add(inner, Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .setMinimumNumberShouldMatch(0)
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST_NOT)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST_NOT)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.FILTER)
            .build();
    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.FILTER)
            .build();
    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "quux")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST_NOT)
            .build();
    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.FILTER)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new TermQuery(new Term("foo", "quux")), Occur.MUST_NOT)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));
  }

  public void testFlattenDisjunctionInMustClause() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    Query inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .build();
    Query query =
        new BooleanQuery.Builder()
            .add(inner, Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    Query expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .setMinimumNumberShouldMatch(1)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));

    inner =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "foo")), Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();
    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    expectedRewritten =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "quux")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "foo")), Occur.SHOULD)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .setMinimumNumberShouldMatch(2)
            .build();
    assertEquals(expectedRewritten, searcher.rewrite(query));
  }

  public void testDiscardShouldClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    Query query1 =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("field", "a")), Occur.MUST)
                .add(new TermQuery(new Term("field", "b")), Occur.SHOULD)
                .build());
    Query rewritten1 = new ConstantScoreQuery(new TermQuery(new Term("field", "a")));
    assertEquals(rewritten1, searcher.rewrite(query1));

    Query query2 =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("field", "a")), Occur.MUST)
                .add(new TermQuery(new Term("field", "b")), Occur.SHOULD)
                .add(new TermQuery(new Term("field", "c")), Occur.FILTER)
                .build());
    Query rewritten2 =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("field", "a")), Occur.FILTER)
                .add(new TermQuery(new Term("field", "c")), Occur.FILTER)
                .build());
    assertEquals(rewritten2, searcher.rewrite(query2));

    Query query3 =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("field", "a")), Occur.SHOULD)
                .add(new TermQuery(new Term("field", "b")), Occur.SHOULD)
                .build());
    assertSame(query3, searcher.rewrite(query3));

    Query query4 =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("field", "a")), Occur.SHOULD)
                .add(new TermQuery(new Term("field", "b")), Occur.MUST_NOT)
                .build());
    assertSame(query4, searcher.rewrite(query4));

    Query query5 =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .setMinimumNumberShouldMatch(1)
                .add(new TermQuery(new Term("field", "a")), Occur.SHOULD)
                .add(new TermQuery(new Term("field", "b")), Occur.SHOULD)
                .add(new TermQuery(new Term("field", "c")), Occur.FILTER)
                .build());
    assertSame(query5, searcher.rewrite(query5));
  }

  public void testShouldMatchNoDocsQuery() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new MatchNoDocsQuery(), Occur.SHOULD)
            .build();
    assertEquals(new TermQuery(new Term("foo", "bar")), searcher.rewrite(query));
  }

  public void testMustNotMatchNoDocsQuery() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
            .add(new MatchNoDocsQuery(), Occur.MUST_NOT)
            .build();
    assertEquals(new TermQuery(new Term("foo", "bar")), searcher.rewrite(query));
  }

  public void testMustMatchNoDocsQuery() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new MatchNoDocsQuery(), Occur.MUST)
            .build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));
  }

  public void testFilterMatchNoDocsQuery() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new MatchNoDocsQuery(), Occur.FILTER)
            .build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));
  }

  public void testEmptyBoolean() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());
    BooleanQuery query = new BooleanQuery.Builder().build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));
  }

  public void testSimplifyFilterClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery query1 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new ConstantScoreQuery(new TermQuery(new Term("foo", "baz"))), Occur.FILTER)
            .build();
    BooleanQuery expected1 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.FILTER)
            .build();
    assertEquals(expected1, searcher.rewrite(query1));

    BooleanQuery query2 =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.FILTER)
            .add(new ConstantScoreQuery(new TermQuery(new Term("foo", "bar"))), Occur.FILTER)
            .build();
    Query expected2 =
        new BoostQuery(new ConstantScoreQuery(new TermQuery(new Term("foo", "bar"))), 0);
    assertEquals(expected2, searcher.rewrite(query2));
  }

  public void testSimplifyMustNotClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new ConstantScoreQuery(new TermQuery(new Term("foo", "baz"))), Occur.MUST_NOT)
            .build();
    BooleanQuery expected =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("foo", "bar")), Occur.MUST)
            .add(new TermQuery(new Term("foo", "baz")), Occur.MUST_NOT)
            .build();
    assertEquals(expected, searcher.rewrite(query));
  }

  public void testSimplifyNonScoringShouldClauses() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    Query query =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
                .add(new ConstantScoreQuery(new TermQuery(new Term("foo", "baz"))), Occur.SHOULD)
                .build());
    Query expected =
        new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("foo", "bar")), Occur.SHOULD)
                .add(new TermQuery(new Term("foo", "baz")), Occur.SHOULD)
                .build());
    assertEquals(expected, searcher.rewrite(query));
  }

  public void testShouldClausesLessThanOrEqualToMinimumNumberShouldMatch() throws IOException {
    IndexSearcher searcher = newSearcher(new MultiReader());

    // The only one SHOULD clause is MatchNoDocsQuery
    BooleanQuery query =
        new BooleanQuery.Builder()
            .add(new PhraseQuery.Builder().build(), Occur.SHOULD)
            .setMinimumNumberShouldMatch(1)
            .build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));
    query =
        new BooleanQuery.Builder()
            .add(new PhraseQuery.Builder().build(), Occur.SHOULD)
            .setMinimumNumberShouldMatch(0)
            .build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));

    // Meaningful SHOULD clause count is less than MinimumNumberShouldMatch
    query =
        new BooleanQuery.Builder()
            .add(new PhraseQuery.Builder().build(), Occur.SHOULD)
            .add(new PhraseQuery.Builder().add(new Term("field", "a")).build(), Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));

    // Meaningful SHOULD clause count is equal to MinimumNumberShouldMatch
    query =
        new BooleanQuery.Builder()
            .add(new PhraseQuery.Builder().add(new Term("field", "b")).build(), Occur.SHOULD)
            .add(
                new PhraseQuery.Builder()
                    .add(new Term("field", "a"))
                    .add(new Term("field", "c"))
                    .build(),
                Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();
    BooleanQuery expected =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("field", "b")), Occur.MUST)
            .add(
                new PhraseQuery.Builder()
                    .add(new Term("field", "a"))
                    .add(new Term("field", "c"))
                    .build(),
                Occur.MUST)
            .build();
    assertEquals(expected, searcher.rewrite(query));

    // Invalid Inner query get removed after rewrite
    Query inner =
        new BooleanQuery.Builder()
            .add(new PhraseQuery.Builder().build(), Occur.SHOULD)
            .add(new PhraseQuery.Builder().add(new Term("field", "a")).build(), Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();

    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.SHOULD)
            .add(new PhraseQuery.Builder().add(new Term("field", "b")).build(), Occur.SHOULD)
            .add(
                new PhraseQuery.Builder()
                    .add(new Term("field", "a"))
                    .add(new Term("field", "c"))
                    .build(),
                Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();
    assertEquals(expected, searcher.rewrite(query));

    query =
        new BooleanQuery.Builder()
            .add(inner, Occur.SHOULD)
            .add(new PhraseQuery.Builder().add(new Term("field", "b")).build(), Occur.SHOULD)
            .setMinimumNumberShouldMatch(2)
            .build();
    assertEquals(new MatchNoDocsQuery(), searcher.rewrite(query));
  }

  // Test query to count number of rewrites for its lifetime.
  private static final class TestRewriteQuery extends Query {

    private int numRewrites = 0;

    @Override
    public String toString(String field) {
      return "TestRewriteQuery{rewrites=" + numRewrites + "}";
    }

    @Override
    public Query rewrite(IndexSearcher indexSearcher) {
      numRewrites++;
      return this;
    }

    @Override
    public void visit(QueryVisitor visitor) {}

    @Override
    public boolean equals(Object obj) {
      return obj instanceof TestRewriteQuery;
    }

    @Override
    public int hashCode() {
      return TestRewriteQuery.class.hashCode();
    }
  }
}
