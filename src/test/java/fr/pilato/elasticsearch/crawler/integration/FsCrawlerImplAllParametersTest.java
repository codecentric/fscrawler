/*
 * Licensed to David Pilato (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.elasticsearch.crawler.integration;

import fr.pilato.elasticsearch.crawler.fs.FsCrawlerImpl;
import fr.pilato.elasticsearch.crawler.fs.client.ElasticsearchClient;
import fr.pilato.elasticsearch.crawler.fs.meta.job.FsJobFileHandler;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.Elasticsearch;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.Fs;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.FsSettings;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.FsSettingsFileHandler;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.FsSettingsParser;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.Percentage;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.Server;
import fr.pilato.elasticsearch.crawler.fs.meta.settings.TimeValue;
import fr.pilato.elasticsearch.crawler.fs.util.FsCrawlerUtil;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test all crawler settings
 */
public class FsCrawlerImplAllParametersTest extends AbstractMonoNodeITest {

    protected FsCrawlerImpl crawler = null;
    protected Path currentTestResourceDir;
    protected Path metadataDir;

    /**
     * We suppose that each test has its own set of files. Even if we duplicate them, that will make the code
     * more readable.
     * The temp folder which is used as a root is automatically cleaned after the test so we don't have to worry
     * about it.
     */
    @Before
    public void copyTestResources() throws IOException {
        Path testResourceTarget = Paths.get(folder.getRoot().toURI()).resolve("resources");
        if (Files.notExists(testResourceTarget)) {
            Files.createDirectory(testResourceTarget);
        }
        metadataDir = Paths.get(folder.getRoot().toURI()).resolve(".fscrawler");
        if (Files.notExists(metadataDir)) {
            Files.createDirectory(metadataDir);
        }

        String currentTestName = getCurrentTestName();
        // We copy files from the src dir to the temp dir
        staticLogger.info("  --> Launching test [{}]", currentTestName);
        String url = getUrl(currentTestName);
        Path from = Paths.get(url);
        currentTestResourceDir = testResourceTarget.resolve(currentTestName);

        staticLogger.info("  --> Copying test resources from [{}]", from);
        if (Files.notExists(from)) {
            logger.error("directory [{}] should be copied to [{}]", from, currentTestResourceDir);
            throw new RuntimeException(from + " doesn't seem to exist. Check your JUnit tests.");
        }

        Files.walkFileTree(from, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new InternalFileVisitor(from, currentTestResourceDir));

        staticLogger.info("  --> Test resources ready in [{}]", currentTestResourceDir);
    }

    @After
    public void shutdownCrawler() {
        stopCrawler();
    }


    private static final String testCrawlerPrefix = "fscrawler_";

    private String getCrawlerName() {
        String testName = testCrawlerPrefix.concat(getCurrentTestName());
        return testName.contains(" ") ? Strings.split(testName, " ")[0] : testName;
    }

    private Fs.Builder startCrawlerDefinition() throws IOException {
        return startCrawlerDefinition(currentTestResourceDir.toString(), TimeValue.timeValueSeconds(5));
    }

    private Fs.Builder startCrawlerDefinition(TimeValue updateRate) throws IOException {
        return startCrawlerDefinition(currentTestResourceDir.toString(), updateRate);
    }

    private Fs.Builder startCrawlerDefinition(String dir) throws IOException {
        return startCrawlerDefinition(dir, TimeValue.timeValueSeconds(5));
    }

    private Fs.Builder startCrawlerDefinition(String dir, TimeValue updateRate) {
        logger.info("  --> creating crawler for dir [{}]", dir);
        return Fs
                .builder()
                .setUrl(dir)
                .setUpdateRate(updateRate);
    }

    private Elasticsearch endCrawlerDefinition(String indexName) {
        return Elasticsearch.builder()
                .setIndex(indexName)
                .addNode(Elasticsearch.Node.builder().setHost("127.0.0.1").setPort(HTTP_TEST_PORT).build())
                .setBulkSize(1)
                .build();
    }

    private File URItoFile(URL url) {
        try {
            return new File(url.toURI());
        } catch(URISyntaxException e) {
            return new File(url.getPath());
        }
    }

    private String getUrl(String dir) {
        URL resource = FsCrawlerImplAllParametersTest.class.getResource("/job-sample.json");
        File resourceDir = new File(URItoFile(resource).getParentFile(), "samples");
        File dataDir = new File(resourceDir, dir);

        return dataDir.getAbsoluteFile().getAbsolutePath();
    }

    private void startCrawler() throws Exception {
        startCrawler(getCrawlerName());
    }

    private void startCrawler(final String jobName) throws Exception {
        startCrawler(jobName, startCrawlerDefinition().build(), endCrawlerDefinition(jobName), null);
    }

    private void startCrawler(final String jobName, Fs fs, Elasticsearch elasticsearch, Server server) throws Exception {
        logger.info("  --> starting crawler [{}]", jobName);

        // TODO do this rarely() createIndex(jobName);

        crawler = new FsCrawlerImpl(metadataDir, FsSettings.builder(jobName).setElasticsearch(elasticsearch).setFs(fs).setServer(server).build());
        crawler.start();

        // We wait up to 10 seconds before considering a failing test
        assertThat("Job meta file should exists in ~/.fscrawler...", awaitBusy(() -> {
            try {
                new FsJobFileHandler(metadataDir).read(jobName);
                return true;
            } catch (IOException e) {
                return false;
            }
        }), equalTo(true));

        countTestHelper(jobName, null, null);

        // Make sure we refresh indexed docs before launching tests
        refresh();

        // Print crawler settings
        FsSettings fsSettings = new FsSettingsFileHandler(metadataDir).read(jobName);

        if(logger.isDebugEnabled()) {
            logger.debug("  --> Index settings [{}]", FsSettingsParser.toJson(fsSettings));
        }
    }

    private void refresh() {
        client.admin().indices().prepareRefresh().get();
    }

    private void createIndex(String index) {
        logger.info("  --> createIndex({})", index);
        client.admin().indices().prepareCreate(index).get();
    }

    private void stopCrawler() {
        logger.info("  --> stopping all crawlers");
        if (crawler != null) {
            staticLogger.info("  --> Stopping crawler");
            crawler.close();
            crawler = null;
        }
    }

    /**
     * Check that we have the expected number of docs or at least one if expected is null
     *
     * @param indexName Index we will search in.
     * @param term      Term you search for. MatchAll if null.
     * @param expected  expected number of docs. Null if at least 1.
     * @return the search response if further tests are needed
     * @throws Exception
     */
    public SearchResponse countTestHelper(final String indexName, String term, final Integer expected) throws Exception {
        return countTestHelper(indexName, term, expected, null, null);
    }

    /**
     * Check that we have the expected number of docs or at least one if expected is null
     *
     * @param indexName Index we will search in.
     * @param term      Term you search for. MatchAll if null.
     * @param expected  expected number of docs. Null if at least 1.
     * @param path      Path we are supposed to scan. If we have not accurate results, we display its content
     * @param esQuery   If we want to use a specific elasticsearch query for a test instead of queryStringQuery or MathAll
     * @param fields    If we want to add some fields within the response
     * @return the search response if further tests are needed
     * @throws Exception
     */
    public SearchResponse countTestHelper(final String indexName, String term, final Integer expected, final Path path,
                                          final QueryBuilder esQuery, final String... fields) throws Exception {
        // Let's search for entries
        final QueryBuilder query;
        if (esQuery != null) {
            query = esQuery;
        } else {
            if (term == null) {
                query = QueryBuilders.matchAllQuery();
            } else {
                query = QueryBuilders.queryStringQuery(term);
            }
        }

        final SearchResponse[] response = new SearchResponse[1];

        // We wait up to 5 seconds before considering a failing test
        assertThat("We waited for 5 seconds but no document has been added", awaitBusy(() -> {
            long totalHits;
            SearchRequestBuilder requestBuilder = client.prepareSearch(indexName)
                    .setTypes(FsCrawlerUtil.INDEX_TYPE_DOC)
                    .setQuery(query);
            for (String field : fields) {
                requestBuilder.addField(field);
            }
            response[0] = requestBuilder.get();
            logger.debug("result {}", response[0].toString());
            totalHits = response[0].getHits().getTotalHits();

            if (expected == null) {
                return (totalHits >= 1);
            } else {
                if (expected == totalHits) {
                    return true;
                } else {
                    logger.info("     ---> expecting [{}] but got [{}] documents in [{}]", expected, totalHits, indexName);
                    if (path != null) {
                        logger.info("     ---> content of [{}]:", path);
                        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path)) {
                            for (Path file : directoryStream) {
                                logger.info("         - {} {}",
                                        file.getFileName().toString(),
                                        Files.getLastModifiedTime(file));
                            }
                        } catch (IOException ex) {
                            logger.error("can not read content of [{}]:", path);
                        }
                    }
                    return false;
                }
            }
        }), equalTo(true));

        return response[0];
    }

    @Test
    @Ignore
    public void test_default_settings() throws Exception {
        ElasticsearchClient client = ElasticsearchClient.builder().build();
        boolean active = client.isActive(Elasticsearch.DEFAULT.getNodes().get(0));
        if (active) {
            logger.warn("you have a local elasticsearch node running on 9200 port. We will skip the test.");
            return;
        }

        try {
            startCrawler(getCrawlerName(), null, null, null);
        } catch (IOException e) {
            // We expect it as we probably don't have an elasticsearch node running with default 9200 port
        }
    }

    @Test
    public void test_filesize() throws Exception {
        startCrawler();

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1);
        for (SearchHit hit : searchResponse.getHits()) {
            Map<String, Object> file = (Map<String, Object>) hit.getSource().get(FsCrawlerUtil.Doc.FILE);
            assertThat(file, notNullValue());
            assertThat(file.get(FsCrawlerUtil.Doc.File.FILESIZE), is(30));
        }
    }

    @Test
    public void test_filesize_limit() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setIndexedChars(new Percentage(7))
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "*");
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.CONTENT), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXED_CHARS), notNullValue());

            // Our original text: "Bonjour David..." should be truncated
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.CONTENT).getValue(), is("Bonjour"));
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXED_CHARS).getValue(), is(7L));
        }
    }

    @Test
    public void test_filesize_limit_percentage() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setIndexedChars(Percentage.parse("0.1%"))
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "*");
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.CONTENT), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXED_CHARS), notNullValue());

            // Our original text: "Bonjour David..." should be truncated
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.CONTENT).getValue(), is("Bonjour "));
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXED_CHARS).getValue(), is(8L));
        }
    }

    @Test
    public void test_filesize_nolimit() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setIndexedChars(new Percentage(-1))
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "*");
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.CONTENT), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXED_CHARS), nullValue());

            // Our original text: "Bonjour David\n\n\n" should not be truncated
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.CONTENT).getValue(), is("Bonjour David\n\n\n"));
        }
    }

    @Test
    public void test_filesize_disabled() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setAddFilesize(false)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1);
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get("filesize"), nullValue());
        }
    }

    @Test
    public void test_includes() throws Exception {
        Fs fs = startCrawlerDefinition()
                .addInclude("*_include.txt")
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);
        countTestHelper(getCrawlerName(), null, 1);
    }

    @Test
    public void test_metadata() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setIndexedChars(new Percentage(1))
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "*");
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.FILENAME), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.CONTENT_TYPE), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.URL), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.FILESIZE), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXING_DATE), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXED_CHARS), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.LAST_MODIFIED), notNullValue());

            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.META + "." + FsCrawlerUtil.Doc.Meta.TITLE), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.META + "." + FsCrawlerUtil.Doc.Meta.KEYWORDS), notNullValue());
        }
    }

    @Test
    public void test_default_metadata() throws Exception {
        startCrawler();

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "*");
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.ATTACHMENT), nullValue());

            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.FILENAME), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.CONTENT_TYPE), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.URL), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.FILESIZE), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXING_DATE), notNullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.INDEXED_CHARS), nullValue());
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.FILE + "." + FsCrawlerUtil.Doc.File.LAST_MODIFIED), notNullValue());

            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.META + "." + FsCrawlerUtil.Doc.Meta.TITLE), notNullValue());
        }
    }

    @Test
    public void test_attributes() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setAttributesSupport(true)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);
        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "attributes.owner");
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.ATTRIBUTES + "." + FsCrawlerUtil.Doc.Attributes.OWNER), notNullValue());
        }
    }

    @Test
    public void test_remove_deleted_enabled() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setRemoveDeleted(true)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        // We should have two docs first
        countTestHelper(getCrawlerName(), null, 2, currentTestResourceDir, null);

        // We remove a file
        logger.info(" ---> Removing file deleted_roottxtfile.txt");
        Files.delete(currentTestResourceDir.resolve("deleted_roottxtfile.txt"));

        // We expect to have one files
        countTestHelper(getCrawlerName(), null, 1, currentTestResourceDir, null);
    }

    @Test
    public void test_remove_deleted_disabled() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setRemoveDeleted(false)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        // We should have two docs first
        countTestHelper(getCrawlerName(), null, 2, currentTestResourceDir, null);

        // We remove a file
        logger.info(" ---> Removing file deleted_roottxtfile.txt");
        Files.delete(currentTestResourceDir.resolve("deleted_roottxtfile.txt"));

        // We expect to have two files
        countTestHelper(getCrawlerName(), null, 2, currentTestResourceDir, null);
    }

    /**
     * Test case for https://github.com/dadoonet/fscrawler/issues/110
     * @throws Exception In case something is wrong
     */
    @Test
    public void test_rename_file() throws Exception {
        startCrawler();

        // We should have two docs first
        countTestHelper(getCrawlerName(), null, 1, currentTestResourceDir, null);

        // We rename the file
        logger.info(" ---> Renaming file roottxtfile.txt to renamed_roottxtfile.txt");
        // We create a copy of a file
        Files.move(currentTestResourceDir.resolve("roottxtfile.txt"),
                currentTestResourceDir.resolve("renamed_roottxtfile.txt"));

        // We expect to have one file only with a new name
        countTestHelper(getCrawlerName(), "file.filename:renamed_roottxtfile.txt", 1, currentTestResourceDir, null);
    }

    /**
     * Test case for issue #60: https://github.com/dadoonet/fscrawler/issues/60 : new files are not added
     */
    @Test
    public void test_add_new_file() throws Exception {
        startCrawler();

        // We should have one doc first
        countTestHelper(getCrawlerName(), null, 1, currentTestResourceDir, null);

        logger.info(" ---> Adding a copy of roottxtfile.txt");
        // We create a copy of a file
        Files.copy(currentTestResourceDir.resolve("roottxtfile.txt"),
                currentTestResourceDir.resolve("new_roottxtfile.txt"));

        // We expect to have two files
        countTestHelper(getCrawlerName(), null, 2, currentTestResourceDir, null);
    }

    @Test
    public void test_add_new_file_with_old_date_before_scan_date() throws Exception {
        startCrawler();

        // We should have one doc first
        countTestHelper(getCrawlerName(), null, 1, currentTestResourceDir, null);

        logger.info(" ---> Adding a copy of roottxtfile.txt");

        // We create a copy of a file and change the last modified date to the past
        Path veryOldFile = currentTestResourceDir.resolve("new_roottxtfile.txt");

        Files.copy(currentTestResourceDir.resolve("roottxtfile.txt"), veryOldFile);

        FileTime dateInThePast = FileTime.fromMillis(0);
        Files.setLastModifiedTime(veryOldFile, dateInThePast);

        // We expect to have two files
        countTestHelper(getCrawlerName(), null, 2, currentTestResourceDir, null);
    }




    /**
     * Test case for issue #5: https://github.com/dadoonet/fscrawler/issues/5 : Support JSon documents
     */
    @Test
    public void test_json_support() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setJsonSupport(true)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        assertThat("We should have 0 doc for tweet in text field...", awaitBusy(() -> {
            SearchResponse searchResponse = client.prepareSearch(getCrawlerName())
                    .setQuery(QueryBuilders.termQuery("text", "tweet")).get();
            return searchResponse.getHits().getTotalHits() == 2;
        }), equalTo(true));
    }

    /**
     * Test case for issue #5: https://github.com/dadoonet/fscrawler/issues/5 : Support JSon documents
     */
    @Test
    public void test_json_disabled() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setJsonSupport(false)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        assertThat("We should have 0 doc for tweet in text field...", awaitBusy(() -> {
            SearchResponse searchResponse = client.prepareSearch(getCrawlerName())
                    .setQuery(QueryBuilders.termQuery("text", "tweet")).get();
            return searchResponse.getHits().getTotalHits() == 0;
        }), equalTo(true));

        assertThat("We should have 2 docs for tweet in _all...", awaitBusy(() -> {
            SearchResponse searchResponse = client.prepareSearch(getCrawlerName())
                    .setQuery(QueryBuilders.queryStringQuery("tweet")).get();
            return searchResponse.getHits().getTotalHits() == 2;
        }), equalTo(true));
    }

    /**
     * Test case for issue #7: https://github.com/dadoonet/fscrawler/issues/7 : JSON support: use filename as ID
     */
    @Test
    public void test_filename_as_id() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setJsonSupport(true)
                .setFilenameAsId(true)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        assertThat("Document should exists with [tweet1] id...", awaitBusy(() -> {
            GetResponse getResponse = client.prepareGet(getCrawlerName(), FsCrawlerUtil.INDEX_TYPE_DOC, "tweet1").get();
            return getResponse.isExists();
        }), equalTo(true));

        assertThat("Document should exists with [tweet2] id...", awaitBusy(() -> {
            GetResponse getResponse = client.prepareGet(getCrawlerName(), FsCrawlerUtil.INDEX_TYPE_DOC, "tweet2").get();
            return getResponse.isExists();
        }), equalTo(true));
    }

    @Test
    public void test_store_source() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setStoreSource(true)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "_source", "*");
        for (SearchHit hit : searchResponse.getHits()) {
            // We check that the field has been stored
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.ATTACHMENT), notNullValue());

            // We check that the field is not part of _source
            assertThat(hit.getSource().get(FsCrawlerUtil.Doc.ATTACHMENT), nullValue());
        }
    }

    @Test
    public void test_do_not_store_source() throws Exception {
        startCrawler();

        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1, null, null, "_source", "*");
        for (SearchHit hit : searchResponse.getHits()) {
            // We check that the field has not been stored
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.ATTACHMENT), nullValue());

            // We check that the field is not part of _source
            assertThat(hit.getSource().get(FsCrawlerUtil.Doc.ATTACHMENT), nullValue());
        }
    }

    @Test
    public void test_defaults() throws Exception {
        startCrawler();

        // We expect to have one file
        SearchResponse searchResponse = countTestHelper(getCrawlerName(), null, 1);

        // The default configuration should not add file attributes
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getFields().get(FsCrawlerUtil.Doc.ATTRIBUTES + "." + FsCrawlerUtil.Doc.Attributes.OWNER), nullValue());
        }

    }

    @Test
    public void test_subdirs() throws Exception {
        startCrawler();

        // We expect to have two files
        countTestHelper(getCrawlerName(), null, 2);
    }

    @Test
    public void test_ignore_dir() throws Exception {
        Fs fs = startCrawlerDefinition()
                .addExclude(".ignore")
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        // We expect to have one file
        countTestHelper(getCrawlerName(), null, 1);
    }

    @Test
    public void test_multiple_crawlers() throws Exception {
        Fs fs1 = startCrawlerDefinition(currentTestResourceDir.resolve("crawler1").toString()).build();
        Fs fs2 = startCrawlerDefinition(currentTestResourceDir.resolve("crawler2").toString()).build();
        startCrawler(getCrawlerName() + "_1", fs1, endCrawlerDefinition(getCrawlerName() + "_1"), null);
        startCrawler(getCrawlerName() + "_2", fs2, endCrawlerDefinition(getCrawlerName() + "_2"), null);
        // We should have one doc in index 1...
        countTestHelper(getCrawlerName() + "_1", null, 1);
        // We should have one doc in index 2...
        countTestHelper(getCrawlerName() + "_2", null, 1);
    }

    @Test
    public void test_filename_analyzer() throws Exception {
        startCrawler();

        // We should have one doc
        countTestHelper(getCrawlerName(), null, 1, null, QueryBuilders.termQuery("file.filename", "roottxtfile.txt"));
    }

    /**
     * Test for #83: https://github.com/dadoonet/fscrawler/issues/83
     */
    @Test
    public void test_time_value() throws Exception {
        Fs fs = startCrawlerDefinition(TimeValue.timeValueHours(1)).build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        // We expect to have one file
        countTestHelper(getCrawlerName(), null, 1);
    }


    /**
     * You have to adapt this test to your own system (login / password and SSH connexion)
     * So this test is disabled by default
     */
    @Test @Ignore
    public void test_ssh() throws Exception {
        String username = "USERNAME";
        String password = "PASSWORD";
        String hostname = "localhost";

        Fs fs = startCrawlerDefinition().build();
        Server server = Server.builder()
                .setHostname(hostname)
                .setUsername(username)
                .setPassword(password)
                .setProtocol(FsCrawlerImpl.PROTOCOL.SSH)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), server);

        countTestHelper(getCrawlerName(), null, 2);
    }

    /**
     * You have to adapt this test to your own system (login / pem file and SSH connexion)
     * So this test is disabled by default
     */
    @Test @Ignore
    public void test_ssh_with_key() throws Exception {
        String username = "USERNAME";
        String path_to_pem_file = "/path/to/private_key.pem";
        String hostname = "localhost";

        Fs fs = startCrawlerDefinition().build();
        Server server = Server.builder()
                .setHostname(hostname)
                .setUsername(username)
                .setPemPath(path_to_pem_file)
                .setProtocol(FsCrawlerImpl.PROTOCOL.SSH)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), server);

        countTestHelper(getCrawlerName(), null, 1);
    }

    /**
     * Test for #87: https://github.com/dadoonet/fscrawler/issues/87
     */
    @Test
    public void test_mp3() throws Exception {
        Fs fs = startCrawlerDefinition(TimeValue.timeValueHours(1)).build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        // We expect to have one file
        countTestHelper(getCrawlerName(), null, 1);
    }

    /**
     * Test for #105: https://github.com/dadoonet/fscrawler/issues/105
     */
    @Test
    public void test_unparsable() throws Exception {
        startCrawler();

        // We expect to have two files
        countTestHelper(getCrawlerName(), null, 2);
    }

    /**
     * Test for #103: https://github.com/dadoonet/fscrawler/issues/103
     */
    @Test
    public void test_index_content() throws Exception {
        Fs fs = startCrawlerDefinition()
                .setIndexContent(false)
                .build();
        startCrawler(getCrawlerName(), fs, endCrawlerDefinition(getCrawlerName()), null);

        // We expect to have one file
        countTestHelper(getCrawlerName(), null, 1);

        countTestHelper(getCrawlerName(), null, 0, null, QueryBuilders.prefixQuery("content", "file"));
        countTestHelper(getCrawlerName(), null, 0, null, QueryBuilders.prefixQuery("file.content_type", "text/plain"));
    }

    @Test
    public void test_bulk_flush() throws Exception {
        Fs fs = startCrawlerDefinition().build();
        startCrawler(getCrawlerName(), fs, Elasticsearch.builder()
                .setIndex(getCrawlerName())
                .addNode(Elasticsearch.Node.builder().setHost("127.0.0.1").setPort(HTTP_TEST_PORT).build())
                .setBulkSize(100)
                .setFlushInterval(TimeValue.timeValueSeconds(2))
                .build(), null);

        countTestHelper(getCrawlerName(), null, 1);
    }

    /**
     * Test for word document
     */
    @Test
    public void test_word() throws Exception {
        startCrawler();

        // We expect to have two files
        countTestHelper(getCrawlerName(), "figure", 1);
    }


    private class InternalFileVisitor extends SimpleFileVisitor<Path> {

        private final Path fromPath;
        private final Path toPath;
        private final StandardCopyOption copyOption;

        public InternalFileVisitor(Path fromPath, Path toPath, StandardCopyOption copyOption) {
            this.fromPath = fromPath;
            this.toPath = toPath;
            this.copyOption = copyOption;
        }

        public InternalFileVisitor(Path fromPath, Path toPath) {
            this(fromPath, toPath, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {

            Path targetPath = toPath.resolve(fromPath.relativize(dir));
            if(!Files.exists(targetPath)){
                Files.createDirectory(targetPath);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

            Files.copy(file, toPath.resolve(fromPath.relativize(file)), copyOption);
            return FileVisitResult.CONTINUE;
        }
    }

}
