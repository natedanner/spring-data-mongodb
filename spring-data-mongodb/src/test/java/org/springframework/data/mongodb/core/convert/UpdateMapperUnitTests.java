/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.core.convert;

import static org.mockito.Mockito.*;
import static org.springframework.data.mongodb.core.DocumentTestUtils.*;
import static org.springframework.data.mongodb.test.util.Assertions.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.DocumentTestUtils;
import org.springframework.data.mongodb.core.mapping.Embedded;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Update.Position;

import com.mongodb.DBRef;

/**
 * Unit tests for {@link UpdateMapper}.
 *
 * @author Oliver Gierke
 * @author Christoph Strobl
 * @author Thomas Darimont
 * @author Mark Paluch
 * @author Pavel Vodrazka
 */
@ExtendWith(MockitoExtension.class)
class UpdateMapperUnitTests {

	@Mock MongoDatabaseFactory factory;
	private MappingMongoConverter converter;
	private MongoMappingContext context;
	private UpdateMapper mapper;

	private Converter<NestedEntity, Document> writingConverterSpy;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {

		this.writingConverterSpy = Mockito.spy(new NestedEntityWriteConverter());
		CustomConversions conversions = new MongoCustomConversions(Collections.singletonList(writingConverterSpy));

		this.context = new MongoMappingContext();
		this.context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
		this.context.initialize();

		this.converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), context);
		this.converter.setCustomConversions(conversions);
		this.converter.afterPropertiesSet();

		this.mapper = new UpdateMapper(converter);
	}

	@Test // DATAMONGO-721
	void updateMapperRetainsTypeInformationForCollectionField() {

		Update update = new Update().push("list", new ConcreteChildClass("2", "BAR"));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document list = getAsDocument(push, "aliased");

		assertTypeHint(list, ConcreteChildClass.class);
	}

	@Test // DATAMONGO-807
	void updateMapperShouldRetainTypeInformationForNestedEntities() {

		Update update = Update.update("model", new ModelImpl(1));
		UpdateMapper mapper = new UpdateMapper(converter);

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ModelWrapper.class));

		Document set = getAsDocument(mappedObject, "$set");
		Document modelDocument = (Document) set.get("model");
		assertTypeHint(modelDocument, ModelImpl.class);
	}

	@Test // DATAMONGO-807
	void updateMapperShouldNotPersistTypeInformationForKnownSimpleTypes() {

		Update update = Update.update("model.value", 1);
		UpdateMapper mapper = new UpdateMapper(converter);

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ModelWrapper.class));

		Document set = getAsDocument(mappedObject, "$set");
		assertThat(set.get("_class")).isNull();
	}

	@Test // DATAMONGO-807
	void updateMapperShouldNotPersistTypeInformationForNullValues() {

		Update update = Update.update("model", null);
		UpdateMapper mapper = new UpdateMapper(converter);

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ModelWrapper.class));

		Document set = getAsDocument(mappedObject, "$set");
		assertThat(set.get("_class")).isNull();
	}

	@Test // DATAMONGO-407
	void updateMapperShouldRetainTypeInformationForNestedCollectionElements() {

		Update update = Update.update("list.$", new ConcreteChildClass("42", "bubu"));

		UpdateMapper mapper = new UpdateMapper(converter);
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document set = getAsDocument(mappedObject, "$set");
		Document modelDocument = getAsDocument(set, "aliased.$");
		assertTypeHint(modelDocument, ConcreteChildClass.class);
	}

	@Test // DATAMONGO-407
	void updateMapperShouldSupportNestedCollectionElementUpdates() {

		Update update = Update.update("list.$.value", "foo").set("list.$.otherValue", "bar");

		UpdateMapper mapper = new UpdateMapper(converter);
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document set = getAsDocument(mappedObject, "$set");
		assertThat(set.get("aliased.$.value")).isEqualTo("foo");
		assertThat(set.get("aliased.$.otherValue")).isEqualTo("bar");
	}

	@Test // DATAMONGO-407
	void updateMapperShouldWriteTypeInformationForComplexNestedCollectionElementUpdates() {

		Update update = Update.update("list.$.value", "foo").set("list.$.someObject", new ConcreteChildClass("42", "bubu"));

		UpdateMapper mapper = new UpdateMapper(converter);
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document document = getAsDocument(mappedObject, "$set");
		assertThat(document.get("aliased.$.value")).isEqualTo("foo");

		Document someObject = getAsDocument(document, "aliased.$.someObject");
		assertThat(someObject).isNotNull().containsEntry("value", "bubu");
		assertTypeHint(someObject, ConcreteChildClass.class);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test // DATAMONGO-812
	void updateMapperShouldConvertPushCorrectlyWhenCalledWithEachUsingSimpleTypes() {

		Update update = new Update().push("values").each("spring", "data", "mongodb");
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Model.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document values = getAsDocument(push, "values");
		List<Object> each = getAsDBList(values, "$each");

		assertThat(push.get("_class")).isNull();
		assertThat(values.get("_class")).isNull();

		assertThat(each).containsExactly("spring", "data", "mongodb");
	}

	@Test // DATAMONGO-812
	void updateMapperShouldConvertPushWhithoutAddingClassInformationWhenUsedWithEvery() {

		Update update = new Update().push("values").each("spring", "data", "mongodb");

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Model.class));
		Document push = getAsDocument(mappedObject, "$push");
		Document values = getAsDocument(push, "values");

		assertThat(push.get("_class")).isNull();
		assertThat(values.get("_class")).isNull();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test // DATAMONGO-812
	void updateMapperShouldConvertPushCorrectlyWhenCalledWithEachUsingCustomTypes() {

		Update update = new Update().push("models").each(new ListModel("spring", "data", "mongodb"));
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ModelWrapper.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document model = getAsDocument(push, "models");
		List<Object> each = getAsDBList(model, "$each");
		List<Object> values = getAsDBList((Document) each.get(0), "values");

		assertThat(values).containsExactly("spring", "data", "mongodb");
	}

	@Test // DATAMONGO-812
	void updateMapperShouldRetainClassInformationForPushCorrectlyWhenCalledWithEachUsingCustomTypes() {

		Update update = new Update().push("models").each(new ListModel("spring", "data", "mongodb"));
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ModelWrapper.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document model = getAsDocument(push, "models");
		List<Document> each = getAsDBList(model, "$each");

		assertTypeHint(each.get(0), ListModel.class);
	}

	@Test // DATAMONGO-812
	void testUpdateShouldAllowMultiplePushEachForDifferentFields() {

		Update update = new Update().push("category").each("spring", "data").push("type").each("mongodb");
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		assertThat(getAsDocument(push, "category")).containsKey("$each");
		assertThat(getAsDocument(push, "type")).containsKey("$each");
	}

	@Test // DATAMONGO-943
	void updatePushEachAtPositionWorksCorrectlyWhenGivenPositiveIndexParameter() {

		Update update = new Update().push("key").atPosition(2).each(Arrays.asList("Arya", "Arry", "Weasel"));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "key");

		assertThat(key.containsKey("$position")).isTrue();
		assertThat(key.get("$position")).isEqualTo(2);
		assertThat(getAsDocument(push, "key")).containsKey("$each");
	}

	@Test // DATAMONGO-943, DATAMONGO-2055
	void updatePushEachAtNegativePositionWorksCorrectly() {

		Update update = new Update().push("key").atPosition(-2).each(Arrays.asList("Arya", "Arry", "Weasel"));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "key");

		assertThat(key.containsKey("$position")).isTrue();
		assertThat(key.get("$position")).isEqualTo(-2);
	}

	@Test // DATAMONGO-943
	void updatePushEachAtPositionWorksCorrectlyWhenGivenPositionFirst() {

		Update update = new Update().push("key").atPosition(Position.FIRST).each(Arrays.asList("Arya", "Arry", "Weasel"));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "key");

		assertThat(key.containsKey("$position")).isTrue();
		assertThat(key.get("$position")).isEqualTo(0);
		assertThat(getAsDocument(push, "key")).containsKey("$each");
	}

	@Test // DATAMONGO-943
	void updatePushEachAtPositionWorksCorrectlyWhenGivenPositionLast() {

		Update update = new Update().push("key").atPosition(Position.LAST).each(Arrays.asList("Arya", "Arry", "Weasel"));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "key");

		assertThat(key).doesNotContainKey("$position");
		assertThat(getAsDocument(push, "key")).containsKey("$each");
	}

	@Test // DATAMONGO-943
	void updatePushEachAtPositionWorksCorrectlyWhenGivenPositionNull() {

		Update update = new Update().push("key").atPosition(null).each(Arrays.asList("Arya", "Arry", "Weasel"));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "key");

		assertThat(key).doesNotContainKey("$position");
		assertThat(getAsDocument(push, "key")).containsKey("$each");
	}

	@Test // DATAMONGO-832
	void updatePushEachWithSliceShouldRenderCorrectly() {

		Update update = new Update().push("key").slice(5).each(Arrays.asList("Arya", "Arry", "Weasel"));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "key");

		assertThat(key).containsKey("$slice").containsEntry("$slice", 5);
		assertThat(key).containsKey("$each");
	}

	@Test // DATAMONGO-832
	void updatePushEachWithSliceShouldRenderWhenUsingMultiplePushCorrectly() {

		Update update = new Update().push("key").slice(5).each(Arrays.asList("Arya", "Arry", "Weasel")).push("key-2")
				.slice(-2).each("The Beggar King", "Viserys III Targaryen");

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "key");

		assertThat(key).containsKey("$slice").containsEntry("$slice", 5);
		assertThat(key.containsKey("$each")).isTrue();

		Document key2 = getAsDocument(push, "key-2");

		assertThat(key2).containsKey("$slice").containsEntry("$slice", -2);
		assertThat(key2).containsKey("$each");
	}

	@Test // DATAMONGO-1141
	void updatePushEachWithValueSortShouldRenderCorrectly() {

		Update update = new Update().push("scores").sort(Direction.DESC).each(42, 23, 68);

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "scores");

		assertThat(key).containsKey("$sort");
		assertThat(key).containsEntry("$sort", -1);
		assertThat(key).containsKey("$each");
	}

	@Test // DATAMONGO-1141
	void updatePushEachWithDocumentSortShouldRenderCorrectly() {

		Update update = new Update().push("list")
				.sort(Sort.by(new Order(Direction.ASC, "value"), new Order(Direction.ASC, "field")))
				.each(Collections.emptyList());

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithList.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key = getAsDocument(push, "list");

		assertThat(key).containsKey("$sort");
		assertThat(key.get("$sort")).isEqualTo(new Document("renamed-value", 1).append("field", 1));
		assertThat(key).containsKey("$each");
	}

	@Test // DATAMONGO-1141
	void updatePushEachWithSortShouldRenderCorrectlyWhenUsingMultiplePush() {

		Update update = new Update().push("authors").sort(Direction.ASC).each("Harry").push("chapters")
				.sort(Sort.by(Direction.ASC, "order")).each(Collections.emptyList());

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(Object.class));

		Document push = getAsDocument(mappedObject, "$push");
		Document key1 = getAsDocument(push, "authors");

		assertThat(key1).containsKey("$sort");
		assertThat(key1).containsEntry("$sort", 1);
		assertThat(key1).containsKey("$each");

		Document key2 = getAsDocument(push, "chapters");

		assertThat(key2).containsKey("$sort");
		assertThat(key2.get("$sort")).isEqualTo(new Document("order", 1));
		assertThat(key2.containsKey("$each")).isTrue();
	}

	@Test // DATAMONGO-410
	void testUpdateMapperShouldConsiderCustomWriteTarget() {

		List<NestedEntity> someValues = Arrays.asList(new NestedEntity("spring"), new NestedEntity("data"),
				new NestedEntity("mongodb"));
		NestedEntity[] array = new NestedEntity[someValues.size()];

		Update update = new Update().pushAll("collectionOfNestedEntities", someValues.toArray(array));
		mapper.getMappedObject(update.getUpdateObject(), context.getPersistentEntity(DomainEntity.class));

		verify(writingConverterSpy, times(3)).convert(Mockito.any(NestedEntity.class));
	}

	@Test // DATAMONGO-404
	void createsDbRefForEntityIdOnPulls() {

		Update update = new Update().pull("dbRefAnnotatedList.id", "2");

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithDBRefCollection.class));

		Document pullClause = getAsDocument(mappedObject, "$pull");
		assertThat(pullClause.get("dbRefAnnotatedList")).isEqualTo(new DBRef("entity", "2"));
	}

	@Test // DATAMONGO-404
	void createsDbRefForEntityOnPulls() {

		Entity entity = new Entity();
		entity.id = "5";

		Update update = new Update().pull("dbRefAnnotatedList", entity);
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithDBRefCollection.class));

		Document pullClause = getAsDocument(mappedObject, "$pull");
		assertThat(pullClause.get("dbRefAnnotatedList")).isEqualTo(new DBRef("entity", entity.id));
	}

	@Test // DATAMONGO-404
	void rejectsInvalidFieldReferenceForDbRef() {

		Update update = new Update().pull("dbRefAnnotatedList.name", "NAME");
		assertThatThrownBy(() -> mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithDBRefCollection.class))).isInstanceOf(MappingException.class);
	}

	@Test // DATAMONGO-404
	void rendersNestedDbRefCorrectly() {

		Update update = new Update().pull("nested.dbRefAnnotatedList.id", "2");
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(Wrapper.class));

		Document pullClause = getAsDocument(mappedObject, "$pull");
		assertThat(pullClause.containsKey("mapped.dbRefAnnotatedList")).isTrue();
	}

	@Test // DATAMONGO-468
	void rendersUpdateOfDbRefPropertyWithDomainObjectCorrectly() {

		Entity entity = new Entity();
		entity.id = "5";

		Update update = new Update().set("dbRefProperty", entity);
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithDBRefCollection.class));

		Document setClause = getAsDocument(mappedObject, "$set");
		assertThat(setClause.get("dbRefProperty")).isEqualTo(new DBRef("entity", entity.id));
	}

	@Test // DATAMONGO-862
	void rendersUpdateAndPreservesKeyForPathsNotPointingToProperty() {

		Update update = new Update().set("listOfInterface.$.value", "expected-value");
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document setClause = getAsDocument(mappedObject, "$set");
		assertThat(setClause.containsKey("listOfInterface.$.value")).isTrue();
	}

	@Test // DATAMONGO-863
	void doesNotConvertRawDocuments() {

		Update update = new Update();
		update.pull("options",
				new Document("_id", new Document("$in", converter.convertToMongoType(Arrays.asList(1L, 2L)))));

		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document setClause = getAsDocument(mappedObject, "$pull");
		Document options = getAsDocument(setClause, "options");
		Document idClause = getAsDocument(options, "_id");
		List<Object> inClause = getAsDBList(idClause, "$in");

		assertThat(inClause).containsExactly(1L, 2L);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test // DATAMONG0-471
	void testUpdateShouldApply$addToSetCorrectlyWhenUsedWith$each() {

		Update update = new Update().addToSet("values").each("spring", "data", "mongodb");
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ListModel.class));

		Document addToSet = getAsDocument(mappedObject, "$addToSet");
		Document values = getAsDocument(addToSet, "values");
		List<Object> each = getAsDBList(values, "$each");

		assertThat(each).containsExactly("spring", "data", "mongodb");
	}

	@Test // DATAMONG0-471
	void testUpdateShouldRetainClassTypeInformationWhenUsing$addToSetWith$eachForCustomTypes() {

		Update update = new Update().addToSet("models").each(new ModelImpl(2014), new ModelImpl(1), new ModelImpl(28));
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ModelWrapper.class));

		Document addToSet = getAsDocument(mappedObject, "$addToSet");

		Document values = getAsDocument(addToSet, "models");
		List each = getAsDBList(values, "$each");

		for (Object updateValue : each) {
			assertTypeHint((Document) updateValue, ModelImpl.class);
		}
	}

	@Test // DATAMONGO-897
	void updateOnDbrefPropertyOfInterfaceTypeWithoutExplicitGetterForIdShouldBeMappedCorrectly() {

		Update update = new Update().set("referencedDocument", new InterfaceDocumentDefinitionImpl("1", "Foo"));
		Document mappedObject = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithReferenceToInterfaceImpl.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedObject, "$set");
		Object model = $set.get("referencedDocument");

		DBRef expectedDBRef = new DBRef("interfaceDocumentDefinitionImpl", "1");
		assertThat(model).isInstanceOf(DBRef.class).isEqualTo(expectedDBRef);
	}

	@Test // DATAMONGO-847
	void updateMapperConvertsNestedQueryCorrectly() {

		Update update = new Update().pull("list", Query.query(Criteria.where("value").in("foo", "bar")));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		Document $pull = DocumentTestUtils.getAsDocument(mappedUpdate, "$pull");
		Document list = DocumentTestUtils.getAsDocument($pull, "aliased");
		Document value = DocumentTestUtils.getAsDocument(list, "value");
		List<Object> $in = DocumentTestUtils.getAsDBList(value, "$in");

		assertThat($in).containsExactly("foo", "bar");
	}

	@Test // DATAMONGO-847
	void updateMapperConvertsPullWithNestedQuerfyOnDBRefCorrectly() {

		Update update = new Update().pull("dbRefAnnotatedList", Query.query(Criteria.where("id").is("1")));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithDBRefCollection.class));

		Document $pull = DocumentTestUtils.getAsDocument(mappedUpdate, "$pull");
		Document list = DocumentTestUtils.getAsDocument($pull, "dbRefAnnotatedList");

		assertThat(list).isEqualTo(new org.bson.Document().append("_id", "1"));
	}

	@Test // DATAMONGO-1077
	void shouldNotRemovePositionalParameter() {

		Update update = new Update();
		update.unset("dbRefAnnotatedList.$");

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithDBRefCollection.class));

		Document $unset = DocumentTestUtils.getAsDocument(mappedUpdate, "$unset");

		assertThat($unset).isEqualTo(new org.bson.Document().append("dbRefAnnotatedList.$", 1));
	}

	@Test // DATAMONGO-1210
	void mappingEachOperatorShouldNotAddTypeInfoForNonInterfaceNonAbstractTypes() {

		Update update = new Update().addToSet("nestedDocs").each(new NestedDocument("nested-1"),
				new NestedDocument("nested-2"));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DocumentWithNestedCollection.class));

		assertThat(mappedUpdate).doesNotContainKey("$addToSet.nestedDocs.$each.[0]._class")
				.doesNotContainKey("$addToSet.nestedDocs.$each.[1]._class");
	}

	@Test // DATAMONGO-1210
	void mappingEachOperatorShouldAddTypeHintForInterfaceTypes() {

		Update update = new Update().addToSet("models").each(new ModelImpl(1), new ModelImpl(2));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ListModelWrapper.class));

		assertThat(mappedUpdate).containsEntry("$addToSet.models.$each.[0]._class", ModelImpl.class.getName());
		assertThat(mappedUpdate).containsEntry("$addToSet.models.$each.[1]._class", ModelImpl.class.getName());
	}

	@Test // DATAMONGO-1210
	void mappingEachOperatorShouldAddTypeHintForAbstractTypes() {

		Update update = new Update().addToSet("list").each(new ConcreteChildClass("foo", "one"),
				new ConcreteChildClass("bar", "two"));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		assertThat(mappedUpdate).containsEntry("$addToSet.aliased.$each.[0]._class", ConcreteChildClass.class.getName());
		assertThat(mappedUpdate).containsEntry("$addToSet.aliased.$each.[1]._class", ConcreteChildClass.class.getName());
	}

	@Test // DATAMONGO-1210
	void mappingShouldOnlyRemoveTypeHintFromTopLevelTypeInCaseOfNestedDocument() {

		WrapperAroundInterfaceType wait = new WrapperAroundInterfaceType();
		wait.interfaceType = new ModelImpl(1);

		Update update = new Update().addToSet("listHoldingConcretyTypeWithInterfaceTypeAttribute").each(wait);
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DomainTypeWithListOfConcreteTypesHavingSingleInterfaceTypeAttribute.class));

		assertThat(mappedUpdate)
				.doesNotContainKey("$addToSet.listHoldingConcretyTypeWithInterfaceTypeAttribute.$each.[0]._class");
		assertThat(mappedUpdate).containsEntry(
				"$addToSet.listHoldingConcretyTypeWithInterfaceTypeAttribute.$each.[0].interfaceType._class",
				ModelImpl.class.getName());
	}

	@Test // DATAMONGO-1210
	void mappingShouldRetainTypeInformationOfNestedListWhenUpdatingConcreteyParentType() {

		ListModelWrapper lmw = new ListModelWrapper();
		lmw.models = Collections.singletonList(new ModelImpl(1));

		Update update = new Update().set("concreteTypeWithListAttributeOfInterfaceType", lmw);
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(DomainTypeWrappingConcreteyTypeHavingListOfInterfaceTypeAttributes.class));

		assertThat(mappedUpdate).doesNotContainKey("$set.concreteTypeWithListAttributeOfInterfaceType._class");
		assertThat(mappedUpdate).containsEntry("$set.concreteTypeWithListAttributeOfInterfaceType.models.[0]._class",
				ModelImpl.class.getName());
	}

	@Test // DATAMONGO-1809
	void pathShouldIdentifyPositionalParameterWithMoreThanOneDigit() {

		Document at2digitPosition = mapper.getMappedObject(new Update()
				.addToSet("concreteInnerList.10.concreteTypeList", new SomeInterfaceImpl("szeth")).getUpdateObject(),
				context.getPersistentEntity(Outer.class));

		Document at3digitPosition = mapper.getMappedObject(new Update()
				.addToSet("concreteInnerList.123.concreteTypeList", new SomeInterfaceImpl("lopen")).getUpdateObject(),
				context.getPersistentEntity(Outer.class));

		assertThat(at2digitPosition).isEqualTo(new Document("$addToSet",
				new Document("concreteInnerList.10.concreteTypeList", new Document("value", "szeth"))));
		assertThat(at3digitPosition).isEqualTo(new Document("$addToSet",
				new Document("concreteInnerList.123.concreteTypeList", new Document("value", "lopen"))));
	}

	@Test // DATAMONGO-1236
	void mappingShouldRetainTypeInformationForObjectValues() {

		Update update = new Update().set("value", new NestedDocument("kaladin"));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObject.class));

		assertThat(mappedUpdate).containsEntry("$set.value.name", "kaladin");
		assertThat(mappedUpdate).containsEntry("$set.value._class", NestedDocument.class.getName());
	}

	@Test // DATAMONGO-1236
	void mappingShouldNotRetainTypeInformationForConcreteValues() {

		Update update = new Update().set("concreteValue", new NestedDocument("shallan"));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObject.class));

		assertThat(mappedUpdate).containsEntry("$set.concreteValue.name", "shallan");
		assertThat(mappedUpdate).doesNotContainKey("$set.concreteValue._class");
	}

	@Test // DATAMONGO-1236
	void mappingShouldRetainTypeInformationForObjectValuesWithAlias() {

		Update update = new Update().set("value", new NestedDocument("adolin"));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithAliasedObject.class));

		assertThat(mappedUpdate).containsEntry("$set.renamed-value.name", "adolin");
		assertThat(mappedUpdate).containsEntry("$set.renamed-value._class", NestedDocument.class.getName());
	}

	@Test // DATAMONGO-1236
	void mappingShouldRetrainTypeInformationWhenValueTypeOfMapDoesNotMatchItsDeclaration() {

		Map<Object, Object> map = Collections.singletonMap("szeth", new NestedDocument("son-son-vallano"));

		Update update = new Update().set("map", map);
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObjectMap.class));

		assertThat(mappedUpdate).containsEntry("$set.map.szeth.name", "son-son-vallano");
		assertThat(mappedUpdate).containsEntry("$set.map.szeth._class", NestedDocument.class.getName());
	}

	@Test // DATAMONGO-1236
	void mappingShouldNotContainTypeInformationWhenValueTypeOfMapMatchesDeclaration() {

		Map<Object, NestedDocument> map = Collections.singletonMap("jasnah", new NestedDocument("kholin"));

		Update update = new Update().set("concreteMap", map);
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObjectMap.class));

		assertThat(mappedUpdate).containsEntry("$set.concreteMap.jasnah.name", "kholin");
		assertThat(mappedUpdate).doesNotContainKey("$set.concreteMap.jasnah._class");
	}

	@Test // DATAMONGO-1250
	@SuppressWarnings("unchecked")
	void mapsUpdateWithBothReadingAndWritingConverterRegistered() {

		CustomConversions conversions = new MongoCustomConversions(Arrays.asList(
				ClassWithEnum.AllocationToStringConverter.INSTANCE, ClassWithEnum.StringToAllocationConverter.INSTANCE));

		MongoMappingContext mappingContext = new MongoMappingContext();
		mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
		mappingContext.afterPropertiesSet();

		MappingMongoConverter converter = new MappingMongoConverter(mock(DbRefResolver.class), mappingContext);
		converter.setCustomConversions(conversions);
		converter.afterPropertiesSet();

		UpdateMapper mapper = new UpdateMapper(converter);

		Update update = new Update().set("allocation", ClassWithEnum.Allocation.AVAILABLE);
		Document result = mapper.getMappedObject(update.getUpdateObject(),
				mappingContext.getPersistentEntity(ClassWithEnum.class));

		assertThat(result).containsEntry("$set.allocation", ClassWithEnum.Allocation.AVAILABLE.code);
	}

	@Test // DATAMONGO-1251
	void mapsNullValueCorrectlyForSimpleTypes() {

		Update update = new Update().set("value", null);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ConcreteChildClass.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set).containsKey("value").containsEntry("value", null);
	}

	@Test // DATAMONGO-1251
	void mapsNullValueCorrectlyForJava8Date() {

		Update update = new Update().set("date", null);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ClassWithJava8Date.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set).containsKey("date").doesNotContainKey("value");
	}

	@Test // DATAMONGO-1251
	void mapsNullValueCorrectlyForCollectionTypes() {

		Update update = new Update().set("values", null);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ListModel.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set).containsKey("values").doesNotContainKey("value");
	}

	@Test // DATAMONGO-1251
	void mapsNullValueCorrectlyForPropertyOfNestedDocument() {

		Update update = new Update().set("concreteValue.name", null);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObject.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set).containsKey("concreteValue.name");
		assertThat($set).containsEntry("concreteValue.name", null);
	}

	@Test // DATAMONGO-1288
	void mapsAtomicIntegerToIntegerCorrectly() {

		Update update = new Update().set("intValue", new AtomicInteger(10));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(SimpleValueHolder.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set.get("intValue")).isEqualTo(10);
	}

	@Test // DATAMONGO-1288
	void mapsAtomicIntegerToPrimitiveIntegerCorrectly() {

		Update update = new Update().set("primIntValue", new AtomicInteger(10));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(SimpleValueHolder.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set.get("primIntValue")).isEqualTo(10);
	}

	@Test // DATAMONGO-1404
	void mapsMinCorrectly() {

		Update update = new Update().min("minfield", 10);
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(SimpleValueHolder.class));

		assertThat(mappedUpdate).containsEntry("$min", new Document("minfield", 10));
	}

	@Test // DATAMONGO-1404
	void mapsMaxCorrectly() {

		Update update = new Update().max("maxfield", 999);
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(SimpleValueHolder.class));

		assertThat(mappedUpdate).containsEntry("$max", new Document("maxfield", 999));
	}

	@Test // DATAMONGO-1423, DATAMONGO-2155
	@SuppressWarnings("unchecked")
	void mappingShouldConsiderCustomConvertersForEnumMapKeys() {

		CustomConversions conversions = new MongoCustomConversions(Arrays.asList(
				ClassWithEnum.AllocationToStringConverter.INSTANCE, ClassWithEnum.StringToAllocationConverter.INSTANCE));

		MongoMappingContext mappingContext = new MongoMappingContext();
		mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
		mappingContext.afterPropertiesSet();

		MappingMongoConverter converter = new MappingMongoConverter(mock(DbRefResolver.class), mappingContext);
		converter.setCustomConversions(conversions);
		converter.afterPropertiesSet();

		UpdateMapper mapper = new UpdateMapper(converter);

		Update update = new Update().set("enumAsMapKey", Collections.singletonMap(ClassWithEnum.Allocation.AVAILABLE, 100));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				mappingContext.getPersistentEntity(ClassWithEnum.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set.containsKey("enumAsMapKey")).isTrue();

		Map enumAsMapKey = $set.get("enumAsMapKey", Map.class);
		assertThat(enumAsMapKey.get("V")).isEqualTo(100);
	}

	@Test // DATAMONGO-1176
	void mappingShouldPrepareUpdateObjectForMixedOperatorsAndFields() {

		Document document = new Document("key", "value").append("$set", new Document("a", "b").append("x", "y"));

		Document mappedObject = mapper.getMappedObject(document, context.getPersistentEntity(SimpleValueHolder.class));

		assertThat(mappedObject.get("$set")).isEqualTo(new Document("a", "b").append("x", "y").append("key", "value"));
		assertThat(mappedObject).hasSize(1);
	}

	@Test // DATAMONGO-1176
	void mappingShouldReturnReplaceObject() {

		Document document = new Document("key", "value").append("a", "b").append("x", "y");

		Document mappedObject = mapper.getMappedObject(document, context.getPersistentEntity(SimpleValueHolder.class));

		assertThat(mappedObject).containsEntry("key", "value");
		assertThat(mappedObject).containsEntry("a", "b");
		assertThat(mappedObject).containsEntry("x", "y");
		assertThat(mappedObject).hasSize(3);
	}

	@Test // DATAMONGO-1176
	void mappingShouldReturnUpdateObject() {

		Document document = new Document("$push", new Document("x", "y")).append("$set", new Document("a", "b"));

		Document mappedObject = mapper.getMappedObject(document, context.getPersistentEntity(SimpleValueHolder.class));

		assertThat(mappedObject).containsEntry("$push", new Document("x", "y"));
		assertThat(mappedObject).containsEntry("$set", new Document("a", "b"));
		assertThat(mappedObject).hasSize(2);
	}

	@Test // DATAMONGO-1486, DATAMONGO-2155
	void mappingShouldConvertMapKeysToString() {

		Update update = new Update().set("map", Collections.singletonMap(25, "#StarTrek50"));
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObjectMap.class));

		Document $set = DocumentTestUtils.getAsDocument(mappedUpdate, "$set");
		assertThat($set.containsKey("map")).isTrue();

		Map mapToSet = $set.get("map", Map.class);
		for (Object key : mapToSet.keySet()) {
			assertThat(key).isInstanceOf(String.class);
		}
	}

	@Test // DATAMONGO-1772
	void mappingShouldAddTypeKeyInListOfInterfaceTypeContainedInConcreteObjectCorrectly() {

		ConcreteInner inner = new ConcreteInner();
		inner.interfaceTypeList = Collections.singletonList(new SomeInterfaceImpl());
		List<ConcreteInner> list = Collections.singletonList(inner);

		Document mappedUpdate = mapper.getMappedObject(new Update().set("concreteInnerList", list).getUpdateObject(),
				context.getPersistentEntity(Outer.class));

		assertThat(mappedUpdate).containsKey("$set.concreteInnerList.[0].interfaceTypeList.[0]._class")
				.doesNotContainKey("$set.concreteInnerList.[0]._class");
	}

	@Test // DATAMONGO-1772
	void mappingShouldAddTypeKeyInListOfAbstractTypeContainedInConcreteObjectCorrectly() {

		ConcreteInner inner = new ConcreteInner();
		inner.abstractTypeList = Collections.singletonList(new SomeInterfaceImpl());
		List<ConcreteInner> list = Collections.singletonList(inner);

		Document mappedUpdate = mapper.getMappedObject(new Update().set("concreteInnerList", list).getUpdateObject(),
				context.getPersistentEntity(Outer.class));

		assertThat(mappedUpdate).containsKey("$set.concreteInnerList.[0].abstractTypeList.[0]._class")
				.doesNotContainKey("$set.concreteInnerList.[0]._class");
	}

	@Test // DATAMONGO-2155
	void shouldPreserveFieldNamesOfMapProperties() {

		Update update = Update
				.fromDocument(new Document("concreteMap", new Document("Name", new Document("name", "fooo"))));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObjectMap.class));

		assertThat(mappedUpdate).isEqualTo(new Document("concreteMap", new Document("Name", new Document("name", "fooo"))));
	}

	@Test // DATAMONGO-2155
	void shouldPreserveExplicitFieldNamesInsideMapProperties() {

		Update update = Update
				.fromDocument(new Document("map", new Document("Value", new Document("renamed-value", "fooo"))));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithMapOfAliased.class));

		assertThat(mappedUpdate)
				.isEqualTo(new Document("map", new Document("Value", new Document("renamed-value", "fooo"))));
	}

	@Test // DATAMONGO-2155
	void shouldMapAliasedFieldNamesInMapsCorrectly() {

		Update update = Update
				.fromDocument(new Document("map", Collections.singletonMap("Value", new Document("value", "fooo"))));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithMapOfAliased.class));

		assertThat(mappedUpdate)
				.isEqualTo(new Document("map", new Document("Value", new Document("renamed-value", "fooo"))));
	}

	@Test // DATAMONGO-2174
	void mappingUpdateDocumentWithExplicitFieldNameShouldBePossible() {

		Document mappedUpdate = mapper.getMappedObject(new Document("AValue", "a value"),
				context.getPersistentEntity(TypeWithFieldNameThatCannotBeDecapitalized.class));

		assertThat(mappedUpdate).isEqualTo(new Document("AValue", "a value"));
	}

	@Test // DATAMONGO-2054
	void mappingShouldAllowPositionAllParameter() {

		Update update = new Update().inc("grades.$[]", 10);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithListOfIntegers.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$inc", new Document("grades.$[]", 10)));
	}

	@Test // DATAMONGO-2054
	void mappingShouldAllowPositionAllParameterWhenPropertyHasExplicitFieldName() {

		Update update = new Update().inc("list.$[]", 10);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$inc", new Document("aliased.$[]", 10)));
	}

	@Test // DATAMONGO-2215
	void mappingShouldAllowPositionParameterWithIdentifier() {

		Update update = new Update().set("grades.$[element]", 10) //
				.filterArray(Criteria.where("element").gte(100));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithListOfIntegers.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set", new Document("grades.$[element]", 10)));
	}

	@Test // DATAMONGO-2215
	void mappingShouldAllowPositionParameterWithIdentifierWhenFieldHasExplicitFieldName() {

		Update update = new Update().set("list.$[element]", 10) //
				.filterArray(Criteria.where("element").gte(100));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set", new Document("aliased.$[element]", 10)));
	}

	@Test // DATAMONGO-2215
	void mappingShouldAllowNestedPositionParameterWithIdentifierWhenFieldHasExplicitFieldName() {

		Update update = new Update().set("list.$[element].value", 10) //
				.filterArray(Criteria.where("element").gte(100));

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(ParentClass.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set", new Document("aliased.$[element].value", 10)));
	}

	@Test // DATAMONGO-1902
	void mappingShouldConsiderValueOfEmbeddedType() {

		Update update = new Update().set("embeddableValue.stringValue", "updated");

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(WithEmbedded.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set", new Document("stringValue", "updated")));
	}

	@Test // DATAMONGO-1902
	void mappingShouldConsiderEmbeddedType() {

		EmbeddableType embeddableType = new EmbeddableType();
		embeddableType.stringValue = "updated";
		embeddableType.listValue = Arrays.asList("val-1", "val-2");
		Update update = new Update().set("embeddableValue", embeddableType);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(WithEmbedded.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set",
				new Document("stringValue", "updated").append("listValue", Arrays.asList("val-1", "val-2"))));
	}

	@Test // DATAMONGO-1902
	void mappingShouldConsiderValueOfPrefixedEmbeddedType() {

		Update update = new Update().set("embeddableValue.stringValue", "updated");

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(WithPrefixedEmbedded.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set", new Document("prefix-stringValue", "updated")));
	}

	@Test // DATAMONGO-1902
	void mappingShouldConsiderPrefixedEmbeddedType() {

		EmbeddableType embeddableType = new EmbeddableType();
		embeddableType.stringValue = "updated";
		embeddableType.listValue = Arrays.asList("val-1", "val-2");

		Update update = new Update().set("embeddableValue", embeddableType);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(WithPrefixedEmbedded.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set",
				new Document("prefix-stringValue", "updated").append("prefix-listValue", Arrays.asList("val-1", "val-2"))));
	}

	@Test // DATAMONGO-1902
	void mappingShouldConsiderNestedPrefixedEmbeddedType() {

		EmbeddableType embeddableType = new EmbeddableType();
		embeddableType.stringValue = "updated";
		embeddableType.listValue = Arrays.asList("val-1", "val-2");

		Update update = new Update().set("withPrefixedEmbedded.embeddableValue", embeddableType);

		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(WrapperAroundWithEmbedded.class));

		assertThat(mappedUpdate).isEqualTo(new Document("$set", new Document("withPrefixedEmbedded",
				new Document("prefix-stringValue", "updated").append("prefix-listValue", Arrays.asList("val-1", "val-2")))));
	}

	@Test // GH-3552
	void numericKeyForMap() {

		Update update = new Update().set("map.601218778970110001827396", "testing");
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObjectMap.class));

		assertThat(mappedUpdate).isEqualTo(Document.parse("{\"$set\": {\"map.601218778970110001827396\": \"testing\"}}"));
	}

	@Test // GH-3552
	void numericKeyInMapOfNestedPath() {

		Update update = new Update().set("map.601218778970110001827396.value", "testing");
		Document mappedUpdate = mapper.getMappedObject(update.getUpdateObject(),
				context.getPersistentEntity(EntityWithObjectMap.class));

		assertThat(mappedUpdate)
				.isEqualTo(Document.parse("{\"$set\": {\"map.601218778970110001827396.value\": \"testing\"}}"));
	}

	static class DomainTypeWrappingConcreteyTypeHavingListOfInterfaceTypeAttributes {
		ListModelWrapper concreteTypeWithListAttributeOfInterfaceType;
	}

	static class DomainTypeWithListOfConcreteTypesHavingSingleInterfaceTypeAttribute {
		List<WrapperAroundInterfaceType> listHoldingConcretyTypeWithInterfaceTypeAttribute;
	}

	static class WrapperAroundInterfaceType {
		Model interfaceType;
	}

	@org.springframework.data.mongodb.core.mapping.Document(collection = "DocumentWithReferenceToInterface")
	interface DocumentWithReferenceToInterface {

		String getId();

		InterfaceDocumentDefinitionWithoutId getReferencedDocument();

	}

	interface InterfaceDocumentDefinitionWithoutId {

		String getValue();
	}

	static class InterfaceDocumentDefinitionImpl implements InterfaceDocumentDefinitionWithoutId {

		@Id String id;
		String value;

		InterfaceDocumentDefinitionImpl(String id, String value) {

			this.id = id;
			this.value = value;
		}

		@Override
		public String getValue() {
			return this.value;
		}

	}

	static class DocumentWithReferenceToInterfaceImpl implements DocumentWithReferenceToInterface {

		private @Id String id;

		@org.springframework.data.mongodb.core.mapping.DBRef //
		private InterfaceDocumentDefinitionWithoutId referencedDocument;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public void setModel(InterfaceDocumentDefinitionWithoutId referencedDocument) {
			this.referencedDocument = referencedDocument;
		}

		@Override
		public InterfaceDocumentDefinitionWithoutId getReferencedDocument() {
			return this.referencedDocument;
		}

	}

	interface Model {}

	static class ModelImpl implements Model {
		public int value;

		ModelImpl(int value) {
			this.value = value;
		}

	}

	public class ModelWrapper {
		Model model;

		public ModelWrapper() {}

		public ModelWrapper(Model model) {
			this.model = model;
		}
	}

	static class ListModelWrapper {

		List<Model> models;
	}

	static class ListModel {

		List<String> values;

		ListModel(String... values) {
			this.values = Arrays.asList(values);
		}
	}

	static class ParentClass {

		String id;

		@Field("aliased") //
		List<? extends AbstractChildClass> list;

		@Field //
		List<Model> listOfInterface;

		public ParentClass(String id, List<? extends AbstractChildClass> list) {
			this.id = id;
			this.list = list;
		}

	}

	static abstract class AbstractChildClass {

		String id;
		String value;
		String otherValue;
		AbstractChildClass someObject;

		AbstractChildClass(String id, String value) {
			this.id = id;
			this.value = value;
			this.otherValue = "other_" + value;
		}
	}

	static class ConcreteChildClass extends AbstractChildClass {

		ConcreteChildClass(String id, String value) {
			super(id, value);
		}
	}

	static class DomainEntity {
		List<NestedEntity> collectionOfNestedEntities;

		public List<NestedEntity> getCollectionOfNestedEntities() {
			return collectionOfNestedEntities;
		}
	}

	static class NestedEntity {
		String name;

		NestedEntity(String name) {
			super();
			this.name = name;
		}

	}

	@WritingConverter
	static class NestedEntityWriteConverter implements Converter<NestedEntity, Document> {

		@Override
		public Document convert(NestedEntity source) {
			return new Document();
		}
	}

	static class DocumentWithDBRefCollection {

		@Id public String id;

		@org.springframework.data.mongodb.core.mapping.DBRef //
		public List<Entity> dbRefAnnotatedList;

		@org.springframework.data.mongodb.core.mapping.DBRef //
		public Entity dbRefProperty;
	}

	static class Entity {

		@Id public String id;
		String name;
	}

	static class Wrapper {

		@Field("mapped") DocumentWithDBRefCollection nested;
	}

	static class DocumentWithNestedCollection {
		List<NestedDocument> nestedDocs;
	}

	static class NestedDocument {

		String name;

		NestedDocument(String name) {
			super();
			this.name = name;
		}
	}

	static class EntityWithObject {

		Object value;
		NestedDocument concreteValue;
	}

	static class EntityWithList {
		List<EntityWithAliasedObject> list;
	}

	static class EntityWithListOfIntegers {
		List<Integer> grades;
	}

	static class EntityWithAliasedObject {

		@Field("renamed-value") Object value;
		Object field;
	}

	static class EntityWithMapOfAliased {
		Map<String, EntityWithAliasedObject> map;
	}

	static class EntityWithObjectMap {

		Map<Object, Object> map;
		Map<Object, NestedDocument> concreteMap;
	}

	static class ClassWithEnum {

		Allocation allocation;
		Map<Allocation, String> enumAsMapKey;

		enum Allocation {

			AVAILABLE("V"), ALLOCATED("A");

			String code;

			Allocation(String code) {
				this.code = code;
			}

			public static Allocation of(String code) {

				for (Allocation value : values()) {
					if (value.code.equals(code)) {
						return value;
					}
				}

				throw new IllegalArgumentException();
			}
		}

		enum AllocationToStringConverter implements Converter<Allocation, String> {

			INSTANCE;

			@Override
			public String convert(Allocation source) {
				return source.code;
			}
		}

		enum StringToAllocationConverter implements Converter<String, Allocation> {

			INSTANCE;

			@Override
			public Allocation convert(String source) {
				return Allocation.of(source);
			}
		}
	}

	static class ClassWithJava8Date {

		LocalDate date;
	}

	static class SimpleValueHolder {

		Integer intValue;
		int primIntValue;
	}

	static class Outer {
		List<ConcreteInner> concreteInnerList;
	}

	static class ConcreteInner {
		List<SomeInterfaceType> interfaceTypeList;
		List<SomeAbstractType> abstractTypeList;
		List<SomeInterfaceImpl> concreteTypeList;
	}

	interface SomeInterfaceType {

	}

	static abstract class SomeAbstractType {

	}

	@AllArgsConstructor
	@NoArgsConstructor
	static class SomeInterfaceImpl extends SomeAbstractType implements SomeInterfaceType {

		String value;
	}

	@Data
	static class TypeWithFieldNameThatCannotBeDecapitalized {

		@Id protected String id;

		@Field("AValue") private Long aValue = 0L;

	}

	static class WrapperAroundWithEmbedded {

		String someValue;
		WithEmbedded withEmbedded;
		WithPrefixedEmbedded withPrefixedEmbedded;
	}

	static class WithEmbedded {

		String id;

		@Embedded.Nullable EmbeddableType embeddableValue;
	}

	static class WithPrefixedEmbedded {

		String id;

		@Embedded.Nullable("prefix-") EmbeddableType embeddableValue;
	}

	static class EmbeddableType {

		String stringValue;
		List<String> listValue;

		@Field("with-at-field-annotation") //
		String atFieldAnnotatedValue;

		@Transient //
		String transientValue;
	}

}
