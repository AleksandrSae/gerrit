// Copyright (C) 2012 The Android Open Source Project
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

package com.google.gerrit.server.restapi.change;

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ChildCollection;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.mail.Address;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.ReviewerResource;
import com.google.gerrit.server.restapi.account.AccountsCollection;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Collection;
import org.eclipse.jgit.errors.ConfigInvalidException;

@Singleton
public class Reviewers implements ChildCollection<ChangeResource, ReviewerResource> {
  private final DynamicMap<RestView<ReviewerResource>> views;
  private final ApprovalsUtil approvalsUtil;
  private final AccountsCollection accounts;
  private final ReviewerResource.Factory resourceFactory;
  private final ListReviewers list;

  @Inject
  Reviewers(
      ApprovalsUtil approvalsUtil,
      AccountsCollection accounts,
      ReviewerResource.Factory resourceFactory,
      DynamicMap<RestView<ReviewerResource>> views,
      ListReviewers list) {
    this.approvalsUtil = approvalsUtil;
    this.accounts = accounts;
    this.resourceFactory = resourceFactory;
    this.views = views;
    this.list = list;
  }

  @Override
  public DynamicMap<RestView<ReviewerResource>> views() {
    return views;
  }

  @Override
  public RestView<ChangeResource> list() {
    return list;
  }

  @Override
  public ReviewerResource parse(ChangeResource rsrc, IdString id)
      throws ResourceNotFoundException, AuthException, IOException, ConfigInvalidException {
    Address address = Address.tryParse(id.get());

    Account.Id accountId = null;
    try {
      accountId = accounts.parse(TopLevelResource.INSTANCE, id).getUser().getAccountId();
    } catch (ResourceNotFoundException e) {
      if (address == null) {
        throw e;
      }
    }
    // See if the id exists as a reviewer for this change
    if (accountId != null && fetchAccountIds(rsrc).contains(accountId)) {
      return resourceFactory.create(rsrc, accountId);
    }

    // See if the address exists as a reviewer on the change
    if (address != null && rsrc.getNotes().getReviewersByEmail().all().contains(address)) {
      return new ReviewerResource(rsrc, address);
    }

    throw new ResourceNotFoundException(id);
  }

  private Collection<Account.Id> fetchAccountIds(ChangeResource rsrc) {
    return approvalsUtil.getReviewers(rsrc.getNotes()).all();
  }
}
