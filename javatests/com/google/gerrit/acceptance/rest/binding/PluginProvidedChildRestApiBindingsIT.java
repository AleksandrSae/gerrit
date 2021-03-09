// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance.rest.binding;

import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;
import static com.google.gerrit.server.change.RobotCommentResource.ROBOT_COMMENT_KIND;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.rest.util.RestApiCallHelper;
import com.google.gerrit.acceptance.rest.util.RestCall;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Change.Id;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.RobotCommentInput;
import com.google.gerrit.extensions.common.FixSuggestionInfo;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.ChildCollection;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.restapi.RestCollectionModifyView;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.restapi.RestResource;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.change.RobotCommentResource;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import org.junit.Test;

/**
 * Tests for checking plugin-provided REST API bindings nested under a core collection.
 *
 * <p>These tests only verify that the plugin-provided REST endpoints are correctly bound, they do
 * not test the functionality of the plugin REST endpoints.
 */
public class PluginProvidedChildRestApiBindingsIT extends AbstractDaemonTest {

  /** Resource to bind a child collection. */
  public static final TypeLiteral<RestView<TestPluginResource>> TEST_KIND =
      new TypeLiteral<RestView<TestPluginResource>>() {};

  private static final String PLUGIN_NAME = "my-plugin";

  private static final ImmutableSet<RestCall> REVISION_TEST_CALLS =
      ImmutableSet.of(
          // Calls that have the plugin name as part of the collection name
          RestCall.get("/changes/%s/revisions/%s/" + PLUGIN_NAME + "~test-collection/"),
          RestCall.get("/changes/%s/revisions/%s/" + PLUGIN_NAME + "~test-collection/1/detail"),
          RestCall.post("/changes/%s/revisions/%s/" + PLUGIN_NAME + "~test-collection/"),
          RestCall.post("/changes/%s/revisions/%s/" + PLUGIN_NAME + "~test-collection/1/update"),
          // Same tests but without the plugin name as part of the collection name. This works as
          // long as there is no core collection with the same name (which takes precedence) and no
          // other plugin binds a collection with the same name. We highly encourage plugin authors
          // to use the fully qualified collection name instead.
          RestCall.get("/changes/%s/revisions/%s/test-collection/"),
          RestCall.get("/changes/%s/revisions/%s/test-collection/1/detail"),
          RestCall.post("/changes/%s/revisions/%s/test-collection/"),
          RestCall.post("/changes/%s/revisions/%s/test-collection/1/update"));

  private static final ImmutableSet<RestCall> ROBOTCOMMENT_TEST_CALLS =
      ImmutableSet.of(RestCall.delete("/changes/%s/revisions/%s/robotcomments/%s"));

  /**
   * Module for all sys bindings.
   *
   * <p>TODO: This should actually just move into MyPluginHttpModule. However, that doesn't work
   * currently. This TODO is for fixing this bug.
   */
  static class MyPluginSysModule extends AbstractModule {
    @Override
    public void configure() {
      install(
          new RestApiModule() {
            @Override
            public void configure() {
              DynamicMap.mapOf(binder(), TEST_KIND);
              child(REVISION_KIND, "test-collection").to(TestChildCollection.class);

              postOnCollection(TEST_KIND).to(TestPostOnCollection.class);
              post(TEST_KIND, "update").to(TestPost.class);
              get(TEST_KIND, "detail").to(TestGet.class);
              delete(ROBOT_COMMENT_KIND).to(TestDelete.class);
            }
          });
    }
  }

  static class TestPluginResource implements RestResource {}

  @Singleton
  static class TestChildCollection
      implements ChildCollection<RevisionResource, TestPluginResource> {
    private final DynamicMap<RestView<TestPluginResource>> views;

    @Inject
    TestChildCollection(DynamicMap<RestView<TestPluginResource>> views) {
      this.views = views;
    }

    @Override
    public RestView<RevisionResource> list() throws RestApiException {
      return (RestReadView<RevisionResource>)
          resource -> Response.ok(ImmutableList.of("one", "two"));
    }

    @Override
    public TestPluginResource parse(RevisionResource parent, IdString id) throws Exception {
      return new TestPluginResource();
    }

    @Override
    public DynamicMap<RestView<TestPluginResource>> views() {
      return views;
    }
  }

  @Singleton
  static class TestPostOnCollection
      implements RestCollectionModifyView<RevisionResource, TestPluginResource, String> {
    @Override
    public Response<String> apply(RevisionResource parentResource, String input) throws Exception {
      return Response.ok("test");
    }
  }

  @Singleton
  static class TestPost implements RestModifyView<TestPluginResource, String> {
    @Override
    public Response<String> apply(TestPluginResource resource, String input) throws Exception {
      return Response.ok("test");
    }
  }

  @Singleton
  static class TestGet implements RestReadView<TestPluginResource> {
    @Override
    public Response<String> apply(TestPluginResource resource) throws Exception {
      return Response.ok("test");
    }
  }

  @Singleton
  static class TestDelete implements RestModifyView<RobotCommentResource, String> {
    @Override
    public Response<?> apply(RobotCommentResource resource, String input) throws Exception {
      return Response.none();
    }
  }

  @Test
  public void testRevisionEndpoints() throws Exception {
    PatchSet.Id patchSetId = createChange().getPatchSetId();
    try (AutoCloseable ignored = installPlugin(PLUGIN_NAME, MyPluginSysModule.class, null, null)) {
      RestApiCallHelper.execute(
          adminRestSession,
          REVISION_TEST_CALLS.asList(),
          String.valueOf(patchSetId.changeId().get()),
          String.valueOf(patchSetId.get()));
    }
  }

  @Test
  public void testRobotCommentEndpoints() throws Exception {
    PatchSet.Id patchSetId = createChange().getPatchSetId();
    String robotCommentUuid = createRobotComment(patchSetId.changeId());
    try (AutoCloseable ignored = installPlugin(PLUGIN_NAME, MyPluginSysModule.class, null, null)) {
      RestApiCallHelper.execute(
          adminRestSession,
          ROBOTCOMMENT_TEST_CALLS.asList(),
          String.valueOf(patchSetId.changeId().get()),
          String.valueOf(patchSetId.get()),
          robotCommentUuid);
    }
  }

  private String createRobotComment(Change.Id changeId) throws Exception {
    addRobotComment(changeId, createRobotCommentInput(PushOneCommit.FILE_NAME));
    return Iterables.getOnlyElement(
            Iterables.getOnlyElement(
                gApi.changes().id(changeId.get()).current().robotComments().values()))
        .id;
  }

  private void addRobotComment(Id changeId, RobotCommentInput robotCommentInput) throws Exception {
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.robotComments =
        Collections.singletonMap(robotCommentInput.path, ImmutableList.of(robotCommentInput));
    reviewInput.message = "Test robot comment";
    reviewInput.tag = ChangeMessagesUtil.AUTOGENERATED_TAG_PREFIX;
    gApi.changes().id(changeId.get()).current().review(reviewInput);
  }

  private static RobotCommentInput createRobotCommentInput(
      String path, FixSuggestionInfo... fixSuggestionInfos) {
    RobotCommentInput in = new RobotCommentInput();
    in.robotId = "happyRobot";
    in.robotRunId = "1";
    in.message = "nit: trailing whitespace";
    in.path = path;
    in.url = "http://www.happy-robot.com";
    in.properties = new HashMap<>();
    in.properties.put("key1", "value1");
    in.properties.put("key2", "value2");
    in.fixSuggestions = Arrays.asList(fixSuggestionInfos);
    return in;
  }
}
