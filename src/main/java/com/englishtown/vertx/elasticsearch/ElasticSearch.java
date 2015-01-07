package com.englishtown.vertx.elasticsearch;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import org.elasticsearch.script.ScriptService;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import javax.inject.Inject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

/**
 * ElasticSearch event bus verticle
 */
public class ElasticSearch extends BusModBase implements Handler<Message<JsonObject>> {

	protected final TransportClientFactory clientFactory;
	private final ElasticSearchConfigurator configurator;
	protected TransportClient client;
	protected String address;

	public static final String CONFIG_ADDRESS = "address";
	public static final String DEFAULT_ADDRESS = "et.vertx.elasticsearch";
	public static final Charset CHARSET_UTF8 = Charset.forName("UTF-8");

	public static final String CONST_ID = "_id";
	public static final String CONST_INDEX = "_index";
	public static final String CONST_INDICES = "_indices";
	public static final String CONST_TYPE = "_type";
	public static final String CONST_VERSION = "_version";
	public static final String CONST_SOURCE = "_source";

	@Inject
	public ElasticSearch(TransportClientFactory clientFactory, ElasticSearchConfigurator configurator) {
		if (clientFactory == null) {
			throw new IllegalArgumentException("clientProvider is null");
		}
		this.clientFactory = clientFactory;
		this.configurator = configurator;
	}

	/**
	 * Start the busmod
	 */
	@Override
	public void start() {
		super.start();

		Settings settings = ImmutableSettings.settingsBuilder()
				.put("cluster.name", configurator.getClusterName())
				.put("client.transport.sniff", configurator.getClientTransportSniff())
				.build();

		client = clientFactory.create(settings);

		for (TransportAddress transportAddress : configurator.getTransportAddresses()) {
			client.addTransportAddress(transportAddress);
		}

		address = config.getString(CONFIG_ADDRESS, DEFAULT_ADDRESS);
		eb.registerHandler(address, this);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stop() {
		client.close();
		client = null;
	}

	/**
	 * Handle an incoming elastic search message
	 */
	@Override
	public void handle(Message<JsonObject> message) {

		try {
			String action = getMandatoryString("action", message);
			if (action == null) {
				return;
			}

			switch (action) {
			case "index":
				doIndex(message);
				break;
			case "get":
				doGet(message);
				break;
			case "search":
				doSearch(message);
				break;
			case "scroll":
				doScroll(message);
				break;
			case "raw":
				doRaw(message);
				break;
			case "templateQuery":
				doTemplateQuery(message);
				break;
			case "bulk":
				doBulk(message);
				break;
			default:
				sendError(message, "Unrecognized action " + action);
				break;
			}

		} catch (Exception e) {
			sendError(message, "Unhandled exception!", e);
		}

	}

	/**
	 * See http://www.elasticsearch.org/guide/reference/api/index_/
	 *
	 * @param message
	 */
	public void doIndex(final Message<JsonObject> message) {

		JsonObject body = message.body();

		final String index = getRequiredIndex(body, message);
		if (index == null) {
			return;
		}

		// type is optional
		String type = body.getString(CONST_TYPE);;

		JsonObject source = body.getObject(CONST_SOURCE);
		if (source == null) {
			sendError(message, CONST_SOURCE + " is required");
			return;
		}

		// id is optional
		String id = body.getString(CONST_ID);

		client.prepareIndex(index, type, id)
		.setSource(source.encode())
		.execute(new ActionListener<IndexResponse>() {
			@Override
			public void onResponse(IndexResponse indexResponse) {
				JsonObject reply = new JsonObject()
				.putString(CONST_INDEX, indexResponse.getIndex())
				.putString(CONST_TYPE, indexResponse.getType())
				.putString(CONST_ID, indexResponse.getId())
				.putNumber(CONST_VERSION, indexResponse.getVersion());
				sendOK(message, reply);
			}

			@Override
			public void onFailure(Throwable e) {
				sendError(message, "Index error: " + e.getMessage(), new RuntimeException(e));
			}
		});

	}

	/**
	 * See http://www.elasticsearch.org/guide/reference/api/index_/
	 *
	 * @param message
	 */
	public void doBulk(final Message<JsonObject> message) {

		JsonObject body = message.body();
		JsonArray docs = body.getArray("docs");
		
		BulkProcessor bulkProcessor = BulkProcessor.builder(client, new BulkProcessor.Listener() {
			@Override
			public void beforeBulk(long executionId, BulkRequest request) {
				//logger.info("Going to execute new bulk composed of {} actions", request.numberOfActions());
			}

			@Override
			public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
				//logger.info("Executed bulk composed of {} actions", request.numberOfActions());
			}

			@Override
			public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
				//logger.warn("Error executing bulk", failure);
			}
		}).setBulkActions(1000).setConcurrentRequests(1).build();

		// read Hbase records
		Iterator<Object> ii = docs.iterator();
		while( ii.hasNext()) {
			
			JsonObject source = (JsonObject) ii.next();	
			
			String index = source.getString(CONST_INDEX);
			if (index == null) {
				return;
			}

			// type is optional
			String type = source.getString(CONST_TYPE);;

			// id is optional
			String id = body.getString(CONST_ID);			

			JsonObject doc = source.getObject(CONST_SOURCE);
			if (doc == null) {
				sendError(message, CONST_SOURCE + " is required");
				return;
			}

			// build ES IndexRequest and add it to bulkProcessor
			IndexRequest iRequest = new IndexRequest(index, type, id);
			iRequest.source(doc);
			bulkProcessor.add(iRequest);
		}
	}

	/**
	 * http://www.elasticsearch.org/guide/reference/java-api/get/
	 *
	 * @param message
	 */
	public void doGet(final Message<JsonObject> message) {

		JsonObject body = message.body();

		final String index = getRequiredIndex(body, message);
		if (index == null) {
			return;
		}

		// type is optional
		String type = body.getString(CONST_TYPE);;

		String id = body.getString(CONST_ID);
		if (id == null) {
			sendError(message, CONST_ID + " is required");
			return;
		}

		client.prepareGet(index, type, id)
		.execute(new ActionListener<GetResponse>() {
			@Override
			public void onResponse(GetResponse getResponse) {
				JsonObject source = (getResponse.isExists() ? new JsonObject(getResponse.getSourceAsString()) : null);
				JsonObject reply = new JsonObject()
				.putString(CONST_INDEX, getResponse.getIndex())
				.putString(CONST_TYPE, getResponse.getType())
				.putString(CONST_ID, getResponse.getId())
				.putNumber(CONST_VERSION, getResponse.getVersion())
				.putObject(CONST_SOURCE, source);
				sendOK(message, reply);
			}

			@Override
			public void onFailure(Throwable e) {
				sendError(message, "Get error: " + e.getMessage(), new RuntimeException(e));
			}
		});

	}

	/**
	 * http://www.elasticsearch.org/guide/reference/api/search/
	 * http://www.elasticsearch.org/guide/reference/query-dsl/
	 *
	 * @param message
	 */
	public void doSearch(final Message<JsonObject> message) {

		JsonObject body = message.body();

		// Get indices to be searched
		String index = body.getString(CONST_INDEX);
		JsonArray indices = body.getArray(CONST_INDICES);
		List<String> list = new ArrayList<>();
		if (index != null) {
			list.add(index);
		}
		if (indices != null) {
			for (int i = 0; i < indices.size(); i++) {
				list.add(indices.<String>get(i));
			}
		}

		SearchRequestBuilder builder = client.prepareSearch(list.toArray(new String[list.size()]));

		// Get types to be searched
		String type = body.getString(CONST_TYPE);
		JsonArray types = body.getArray("_types");
		list.clear();
		if (type != null) {
			list.add(type);
		}
		if (types != null) {
			for (int i = 0; i < types.size(); i++) {
				list.add(types.<String>get(i));
			}
		}
		if (!list.isEmpty()) {
			builder.setTypes(list.toArray(new String[list.size()]));
		}

		// Set the query
		JsonObject query = body.getObject("query");
		if (query != null) {
			builder.setQuery(query.encode());
		}

		// Set the filter
		JsonObject filter = body.getObject("filter");
		if (filter != null) {
			builder.setPostFilter(filter.encode());
		}

		// Set facets
		JsonObject facets = body.getObject("facets");
		if (facets != null) {
			builder.setFacets(facets.encode().getBytes(CHARSET_UTF8));
		}

		// Set search type
		String searchType = body.getString("search_type");
		if (searchType != null) {
			builder.setSearchType(searchType);
		}

		// Set scroll keep alive time
		String scroll = body.getString("scroll");
		if (scroll != null) {
			builder.setScroll(scroll);
		}

		// Set Size
		Integer size = body.getInteger("size");
		if (size != null) {
			builder.setSize(size);
		}

		//Set requested fields
		JsonArray fields = body.getArray("fields");
		if (fields != null) {
			for (int i = 0; i < fields.size(); i++) {
				builder.addField(fields.<String>get(i));
			}
		}

		//Set query timeout
		Long queryTimeout = body.getLong("timeout");
		if (queryTimeout != null) {
			builder.setTimeout(new TimeValue(queryTimeout));
		}

		builder.execute(new ActionListener<SearchResponse>() {
			@Override
			public void onResponse(SearchResponse searchResponse) {
				handleActionResponse(searchResponse, message);
			}

			@Override
			public void onFailure(Throwable e) {
				sendError(message, "Search error: " + e.getMessage(), new RuntimeException(e));
			}
		});

	}

	/**
	 * http://www.elasticsearch.org/guide/reference/api/search/scroll/
	 *
	 * @param message
	 */
	public void doScroll(final Message<JsonObject> message) {

		JsonObject body = message.body();
		String scrollId = body.getString("_scroll_id");
		if (scrollId == null) {
			sendError(message, "_scroll_id is required");
			return;
		}

		String scroll = body.getString("scroll");
		if (scroll == null) {
			sendError(message, "scroll is required");
			return;
		}

		client.prepareSearchScroll(scrollId)
		.setScroll(scroll)
		.execute(new ActionListener<SearchResponse>() {
			@Override
			public void onResponse(SearchResponse searchResponse) {
				handleActionResponse(searchResponse, message);
			}

			@Override
			public void onFailure(Throwable e) {
				sendError(message, "Scroll error: " + e.getMessage(), new RuntimeException(e));
			}
		});

	}

	/**
	 * Send raw DSL query
	 * 
	 * @param message
	 */
	public void doRaw(final Message<JsonObject> message) {

		JsonObject body = message.body();

		JsonObject query = body.getObject("query");

		// Get index to be searched
		String index = body.getString(CONST_INDEX);

		SearchResponse res;

		// Get type to be searched
		if (body.containsField(CONST_TYPE)) {
			String type = body.getString(CONST_TYPE);
			 res = client.search(Requests.searchRequest(index).types(type).source(query.toString())).actionGet();
		} else {
			 res = client.search(Requests.searchRequest(index).source(query.toString())).actionGet();
		}
		handleActionResponse(res, message);    	
	}

	/**
	 Execute an indexed query template

	 Example:

	 {
	 	"template": {
	 	"id": "ghs.products.default"
	 },
		 "params": {
			 "query": "fish",
			 "limit": 10,
			 "offset":0,
			 "fields":"\"id\",\"name\",\"description\""
	 	}
	 }
	 */
	public void doTemplateQuery(final Message<JsonObject> message) {
		String index = null;
		JsonObject body = message.body();

		// query by index if specified
		if (message.body().containsField(CONST_INDEX)) {
			index = message.body().getString(CONST_INDEX);
		}

		// get template to execute
		String templateId = body.getObject("query").getObject("template").getString("id");
		Map<String,Object> params = body.getObject("query").getObject("params").toMap();

		// convert to Map<String,String>
		Map<String,String> stringParams = new HashMap<>();
		Iterator it = params.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry param = (Map.Entry)it.next();
			stringParams.put(param.getKey().toString(), param.getValue().toString());
			it.remove(); // avoids a ConcurrentModificationException
		}

		SearchResponse res = client.prepareSearch()
				.setIndices(index)
				.setTemplateName(templateId)
				.setTemplateType(ScriptService.ScriptType.INDEXED)
				.setTemplateParams(stringParams)
				.get();

		handleActionResponse(res, message);
	}

	protected String getRequiredIndex(JsonObject json, Message<JsonObject> message) {
		String index = json.getString(CONST_INDEX);
		if (index == null || index.isEmpty()) {
			sendError(message, CONST_INDEX + " is required");
			return null;
		}
		return index;
	}

	protected void handleActionResponse(ToXContent toXContent, Message<JsonObject> message) {

		try {
			XContentBuilder builder = XContentFactory.jsonBuilder();
			builder.startObject();
			toXContent.toXContent(builder, SearchResponse.EMPTY_PARAMS);
			builder.endObject();

			JsonObject response = new JsonObject(builder.string());
			sendOK(message, response);

		} catch (IOException e) {
			sendError(message, "Error reading search response: " + e.getMessage(), e);
		}

	}
}
