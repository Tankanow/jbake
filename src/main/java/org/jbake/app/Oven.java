package org.jbake.app;

import java.io.File;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.record.impl.ODocument;
import org.apache.commons.configuration.CompositeConfiguration;
import org.jbake.model.DocumentTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All the baking happens in the Oven!
 *
 * @author Jonathan Bullock <jonbullock@gmail.com>
 *
 */
public class Oven {

    private final static Logger LOGGER = LoggerFactory.getLogger(Oven.class);

    private final static Pattern TEMPLATE_DOC_PATTERN = Pattern.compile("(?:template\\.)([a-zA-Z0-9]+)(?:\\.file)");

    private CompositeConfiguration config;
	private File source;
	private File destination;
	private File templatesPath;
	private File contentsPath;
	private File assetsPath;
    private boolean isClearCache;

	/**
	 * Creates a new instance of the Oven with references to the source and destination folders.
	 *
	 * @param source		The source folder
	 * @param destination	The destination folder
	 * @throws Exception
	 */
	public Oven(File source, File destination, boolean isClearCache) throws Exception {
		this.source = source;
		this.destination = destination;
        this.config = ConfigUtil.load(source);
        this.isClearCache = isClearCache;
	}

    public CompositeConfiguration getConfig() {
        return config;
    }

    public void setConfig(final CompositeConfiguration config) {
        this.config = config;
    }

    private void ensureSource() throws Exception {
        if (!FileUtil.isExistingFolder(source)) {
            throw new Exception("Error: Source folder must exist!");
        }
        if (!source.canRead()) {
            throw new Exception("Error: Source folder is not readable!");
        }
    }


    private void ensureDestination() throws Exception {
        if (null == destination) {
            destination = new File(config.getString("destination.folder"));
        }
        if (!destination.exists()) {
            destination.mkdirs();
        }
        if (!destination.canWrite()) {
            throw new Exception("Error: Destination folder is not writable!");
        }
    }

	/**
	 * Checks source path contains required sub-folders (i.e. templates) and setups up variables for them.
	 *
	 * @throws Exception If template or contents folder don't exist
	 */
	public void setupPaths() throws Exception {
		ensureSource();
        templatesPath = setupRequiredFolderFromConfig("template.folder");
        contentsPath = setupRequiredFolderFromConfig("content.folder");
        assetsPath = setupPathFromConfig("asset.folder");
		if (!assetsPath.exists()) {
			LOGGER.warn("No asset folder was found!");
		}
		ensureDestination();
	}

    private File setupPathFromConfig(String key) {
        return new File(source, config.getString(key));
    }

    private File setupRequiredFolderFromConfig(String key) throws Exception {
        File path = setupPathFromConfig(key);
        if (!FileUtil.isExistingFolder(path)) {
            throw new Exception("Error: Required folder cannot be found! Expected to find [" + key + "] at: " + path.getCanonicalPath());
        }
        return path;
    }

	/**
	 * All the good stuff happens in here...
	 *
	 * @throws Exception
	 */
	public void bake() throws Exception {
        ODatabaseDocumentTx db = DBUtil.createDB(config.getString("db.store"), config.getString("db.path"));
        updateDocTypesFromConfiguration();
        DBUtil.updateSchema(db);
        try {
            long start = new Date().getTime();
            LOGGER.info("Baking has started...");
            clearCacheIfNeeded(db);

            // process source content
            Crawler crawler = new Crawler(db, source, config);
            crawler.crawl(contentsPath);
            LOGGER.info("Pages : {}", crawler.getPageCount());
            LOGGER.info("Posts : {}", crawler.getPostCount());

            Renderer renderer = new Renderer(db, destination, templatesPath, config);

            int renderedCount = 0;
            int errorCount = 0;

            for (String docType : DocumentTypes.getDocumentTypes()) {
                DocumentIterator pagesIt = DBUtil.fetchDocuments(db, "select * from "+docType+" where rendered=false");
                while (pagesIt.hasNext()) {
                    Map<String, Object> page = pagesIt.next();
                    try {
                        renderer.render(page);
                        renderedCount++;
                    } catch (Exception e) {
                        errorCount++;
                    }
                }
            }

            // write index file
            if (config.getBoolean("render.index")) {
                renderer.renderIndex(config.getString("index.file"));
            }

            // write feed file
            if (config.getBoolean("render.feed")) {
                renderer.renderFeed(config.getString("feed.file"));
            }

            // write sitemap file
            if (config.getBoolean("render.sitemap")) {
                renderer.renderSitemap(config.getString("sitemap.file"));
            }

            // write master archive file
            if (config.getBoolean("render.archive")) {
                renderer.renderArchive(config.getString("archive.file"));
            }

            // write tag files
            if (config.getBoolean("render.tags")) {
                renderer.renderTags(crawler.getTags(), config.getString("tag.path"));
            }

            // mark docs as rendered
            for (String docType : DocumentTypes.getDocumentTypes()) {
                DBUtil.update(db, "update "+docType+" set rendered=true where rendered=false and cached=true");
            }
            // copy assets
            Asset asset = new Asset(source, destination);
            asset.copy(assetsPath);

            LOGGER.info("Backing finished!");
            long end = new Date().getTime();
            LOGGER.info("Baked {} items in {}ms", renderedCount, end - start);
            if (errorCount > 0) {
                LOGGER.error("Failed to bake {} item(s)!", errorCount);
            }
        } finally {
            db.close();
        }
    }

    /**
     * Iterates over the configuration, searching for keys like "template.index.file=..."
     * in order to register new document types.
     */
    private void updateDocTypesFromConfiguration() {
        Iterator<String> keyIterator = config.getKeys();
        while (keyIterator.hasNext()) {
            String key = keyIterator.next();
            Matcher matcher = TEMPLATE_DOC_PATTERN.matcher(key);
            if (matcher.find()) {
                DocumentTypes.addDocumentType(matcher.group(1));
            }
        }
    }

    private void clearCacheIfNeeded(final ODatabaseDocumentTx db) {
        boolean needed = isClearCache;
        if (!needed) {
            List<ODocument> docs = DBUtil.query(db, "select sha1 from Signatures where key='templates'");
            String currentTemplatesSignature;
            try {
                currentTemplatesSignature = FileUtil.sha1(templatesPath);
            } catch (Exception e) {
                currentTemplatesSignature = "";
            }
            if (!docs.isEmpty()) {
                String sha1 = docs.get(0).field("sha1");
                needed = !sha1.equals(currentTemplatesSignature);
                if (needed) {
                    DBUtil.update(db, "update Signatures set sha1=? where key='templates'", currentTemplatesSignature);
                }
            } else {
                // first computation of templates signature
                DBUtil.update(db, "insert into Signatures(key,sha1) values('templates',?)", currentTemplatesSignature);
                needed = true;
            }
        }
        if (needed) {
            for (String docType : DocumentTypes.getDocumentTypes()) {
                try {
                    DBUtil.update(db,"delete from "+docType);
                } catch (Exception e) {
                    // maybe a non existing document type
                }
            }
            DBUtil.updateSchema(db);
        }
    }
}
