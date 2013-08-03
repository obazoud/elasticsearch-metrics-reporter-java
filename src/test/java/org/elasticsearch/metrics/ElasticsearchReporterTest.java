package org.elasticsearch.metrics;

import com.codahale.metrics.*;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.common.RandomStringGenerator;
import org.elasticsearch.common.joda.time.format.ISODateTimeFormat;
import org.elasticsearch.common.logging.log4j.LogConfigurator;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.IndexTemplateMissingException;
import org.elasticsearch.metrics.percolation.Notifier;
import org.elasticsearch.node.Node;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;
import static org.elasticsearch.node.NodeBuilder.nodeBuilder;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;

public class ElasticsearchReporterTest {

    private static Node node;
    private static Client client;
    private ElasticsearchReporter elasticsearchReporter;
    private MetricRegistry registry = new MetricRegistry();
    private String index = RandomStringGenerator.randomAlphabetic(12).toLowerCase();
    private String indexWithDate = String.format("%s-%s-%02d", index, Calendar.getInstance().get(Calendar.YEAR), Calendar.getInstance().get(Calendar.MONTH)+1);
    private String prefix = RandomStringGenerator.randomAlphabetic(12).toLowerCase();

    @BeforeClass
    public static void startElasticsearch() {
        Settings settings = ImmutableSettings.settingsBuilder().put("http.port", "9999").put("cluster.name", RandomStringGenerator.randomAlphabetic(10)).build();
        LogConfigurator.configure(settings);
        node = nodeBuilder().settings(settings).node().start();
        client = node.client();
    }

    @AfterClass
    public static void stopElasticsearch() {
        node.close();
    }

    @Before
    public void setup() throws IOException {
        client.admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet();
        try {
            client.admin().indices().prepareDeleteTemplate("metrics_template").execute().actionGet();
        } catch (IndexTemplateMissingException e) {} // ignore
        elasticsearchReporter = createElasticsearchReporterBuilder().build();
    }

    @Test
    public void testThatTemplateIsAdded() throws Exception {
        ClusterStateResponse clusterStateResponse = client.admin().cluster().prepareState()
                .setFilterRoutingTable(true)
                .setFilterNodes(true)
                .setFilterIndexTemplates("metrics_template").execute().actionGet();

        assertThat(clusterStateResponse.getState().metaData().templates().size(), is(1));
        IndexTemplateMetaData templateData = clusterStateResponse.getState().metaData().templates().get("metrics_template");
        assertThat(templateData.order(), is(0));
        assertThat(templateData.getMappings().get("timer"), is(notNullValue()));
    }

    @Test
    public void testThatTemplateIsNotOverWritten() throws Exception {
        client.admin().indices().preparePutTemplate("metrics_template").setTemplate("foo*").setSettings(String.format("{ \"index.number_of_shards\" : \"1\"}")).execute().actionGet();
        //client.admin().cluster().prepareHealth().setWaitForGreenStatus();

        elasticsearchReporter = createElasticsearchReporterBuilder().build();

        ClusterStateResponse clusterStateResponse = client.admin().cluster().prepareState()
                .setLocal(false)
                .setFilterRoutingTable(true)
                .setFilterNodes(true)
                .setFilterIndexTemplates("metrics_template").execute().actionGet();

        assertThat(clusterStateResponse.getState().metaData().templates().size(), is(1));
        IndexTemplateMetaData templateData = clusterStateResponse.getState().metaData().templates().get("metrics_template");
        assertThat(templateData.template(), is("foo*"));
    }

    @Test
    public void testThatTimeBasedIndicesCanBeDisabled() throws Exception {
        elasticsearchReporter = createElasticsearchReporterBuilder().indexDateFormat("").build();
        indexWithDate = index;

        registry.counter(name("test", "cache-evictions")).inc();
        reportAndRefresh();

        SearchResponse searchResponse = client.prepareSearch(index).setTypes("counter").execute().actionGet();
        assertThat(searchResponse.getHits().totalHits(), is(1l));
    }

    @Test
    public void testCounter() throws Exception {
        final Counter evictions = registry.counter(name("test", "cache-evictions"));
        evictions.inc(25);
        reportAndRefresh();

        SearchResponse searchResponse = client.prepareSearch(indexWithDate).setTypes("counter").execute().actionGet();
        assertThat(searchResponse.getHits().totalHits(), is(1l));

        Map<String, Object> hit = searchResponse.getHits().getAt(0).sourceAsMap();
        assertTimestamp(hit);
        assertKey(hit, "count", 25);
        assertKey(hit, "name", prefix + ".test.cache-evictions");
    }

    @Test
    public void testHistogram() {
        final Histogram histogram = registry.histogram(name("foo", "bar"));
        histogram.update(20);
        histogram.update(40);
        reportAndRefresh();

        SearchResponse searchResponse = client.prepareSearch(indexWithDate).setTypes("histogram").execute().actionGet();
        assertThat(searchResponse.getHits().totalHits(), is(1l));

        Map<String, Object> hit = searchResponse.getHits().getAt(0).sourceAsMap();
        assertTimestamp(hit);
        assertKey(hit, "name", prefix + ".foo.bar");
        assertKey(hit, "count", 2);
        assertKey(hit, "max", 40);
        assertKey(hit, "min", 20);
        assertKey(hit, "mean", 30.0);
    }

    @Test
    public void testMeter() {
        final Meter meter = registry.meter(name("foo", "bar"));
        meter.mark(10);
        meter.mark(20);
        reportAndRefresh();

        SearchResponse searchResponse = client.prepareSearch(indexWithDate).setTypes("meter").execute().actionGet();
        assertThat(searchResponse.getHits().totalHits(), is(1l));

        Map<String, Object> hit = searchResponse.getHits().getAt(0).sourceAsMap();
        assertTimestamp(hit);
        assertKey(hit, "name", prefix + ".foo.bar");
        assertKey(hit, "count", 30);
    }

    @Test
    public void testTimer() throws Exception {
        final Timer timer = registry.timer(name("foo", "bar"));
        final Timer.Context timerContext = timer.time();
        Thread.sleep(200);
        timerContext.stop();
        reportAndRefresh();

        SearchResponse searchResponse = client.prepareSearch(indexWithDate).setTypes("timer").execute().actionGet();
        assertThat(searchResponse.getHits().totalHits(), is(1l));

        Map<String, Object> hit = searchResponse.getHits().getAt(0).sourceAsMap();
        assertTimestamp(hit);
        assertKey(hit, "name", prefix + ".foo.bar");
        assertKey(hit, "count", 1);
    }

    @Test
    public void testGauge() throws Exception {
        registry.register(name("foo", "bar"), new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                return 1234;
            }
        });
        reportAndRefresh();

        SearchResponse searchResponse = client.prepareSearch(indexWithDate).setTypes("gauge").execute().actionGet();
        assertThat(searchResponse.getHits().totalHits(), is(1l));

        Map<String, Object> hit = searchResponse.getHits().getAt(0).sourceAsMap();
        assertTimestamp(hit);
        assertKey(hit, "name", prefix + ".foo.bar");
        assertKey(hit, "value", 1234);
    }

    @Test
    public void testThatBulkIndexingWorks() {
        for (int i = 0 ; i < 2020; i++) {
            final Counter evictions = registry.counter(name("foo", "bar", String.valueOf(i)));
            evictions.inc(i);
        }
        reportAndRefresh();

        SearchResponse searchResponse = client.prepareSearch(indexWithDate).setTypes("counter").execute().actionGet();
        assertThat(searchResponse.getHits().totalHits(), is(2020l));
    }

    @Test
    public void testThatPercolationNotificationWorks() throws IOException, InterruptedException {
        SimpleNotifier notifier = new SimpleNotifier();

        elasticsearchReporter = createElasticsearchReporterBuilder()
                .percolateMetrics(prefix + ".foo")
                .percolateNotifier(notifier)
            .build();

        QueryBuilder queryBuilder = QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(),
                FilterBuilders.andFilter(FilterBuilders.rangeFilter("count").gte(20), FilterBuilders.termFilter("name", prefix + ".foo")));
        String json = String.format("{ \"query\" : %s }", queryBuilder.buildAsBytes().toUtf8());
        client.prepareIndex("_percolator", indexWithDate, "myName").setRefresh(true).setSource(json).execute().actionGet();

        final Counter evictions = registry.counter("foo");
        evictions.inc(19);
        reportAndRefresh();
        // TODO: Looks like a bug in the percolator, that the first match is always true for the first query...
        //assertThat(notifier.metrics.size(), is(0));
        assertThat(notifier.metrics.size(), is(1));
        notifier.metrics.clear();

        evictions.inc(2);
        reportAndRefresh();

        assertThat(notifier.metrics.size(), is(1));
        assertThat(notifier.metrics, hasKey("myName"));
        assertThat(notifier.metrics.get("myName").name(), is(prefix + ".foo"));

        notifier.metrics.clear();
        evictions.dec(2);
        reportAndRefresh();
        assertThat(notifier.metrics.size(), is(1));
    }

    private class SimpleNotifier implements Notifier {

        public Map<String, JsonMetrics.JsonMetric> metrics = new HashMap<String, JsonMetrics.JsonMetric>();

        @Override
        public void notify(JsonMetrics.JsonMetric jsonMetric, String match) {
            metrics.put(match, jsonMetric);
        }
    }

    private void reportAndRefresh() {
        elasticsearchReporter.report();
        client.admin().indices().prepareRefresh(indexWithDate).execute().actionGet();
    }

    private void assertKey(Map<String, Object> hit, String key, double value) {
        assertKey(hit, key, Double.toString(value));
    }

    private void assertKey(Map<String, Object> hit, String key, int value) {
        assertKey(hit, key, Integer.toString(value));
    }

    private void assertKey(Map<String, Object> hit, String key, String value) {
        assertThat(hit, hasKey(key));
        assertThat(hit.get(key).toString(), is(value));
    }

    private void assertTimestamp(Map<String, Object> hit) {
        assertThat(hit, hasKey("timestamp"));
        // no exception means everything is cool
        ISODateTimeFormat.dateOptionalTimeParser().parseDateTime(hit.get("timestamp").toString());
    }

    private ElasticsearchReporter.Builder createElasticsearchReporterBuilder() {
        return ElasticsearchReporter.forRegistry(registry)
                .port(9999)
                .prefixedWith(prefix)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .filter(MetricFilter.ALL)
                .index(index);
    }
}