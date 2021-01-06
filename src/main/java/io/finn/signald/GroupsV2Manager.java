/*
 * Copyright (C) 2020 Finn Herzfeld
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.finn.signald;

import io.finn.signald.clientprotocol.v1.JsonAddress;
import io.finn.signald.clientprotocol.v1.JsonGroupJoinInfo;
import io.finn.signald.storage.Group;
import io.finn.signald.storage.GroupsV2Storage;
import io.finn.signald.storage.ProfileAndCredentialEntry;
import io.finn.signald.storage.ProfileCredentialStore;
import io.finn.signald.util.GroupsUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.signal.storageservice.protos.groups.GroupChange;
import org.signal.storageservice.protos.groups.GroupInviteLink;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedGroupJoinInfo;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.auth.AuthCredentialResponse;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.groupsv2.*;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;
import org.whispersystems.signalservice.api.push.exceptions.ConflictException;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.exceptions.NotInGroupException;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.util.Base64;
import org.whispersystems.util.Base64UrlSafe;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class GroupsV2Manager {
  private final GroupsV2Api groupsV2Api;
  private final GroupsV2Storage storage;
  private final ProfileCredentialStore profileCredentialStore;
  private final UUID self;
  private final static GroupsV2Operations groupsV2Operations = GroupsUtil.GetGroupsV2Operations(Manager.serviceConfiguration);
  private final static Logger logger = LogManager.getLogger();
  public GroupsV2Manager(GroupsV2Api groupsV2Api, GroupsV2Storage storage, ProfileCredentialStore profileCredentialStore, UUID self) {
    this.groupsV2Api = groupsV2Api;
    this.storage = storage;
    this.profileCredentialStore = profileCredentialStore;
    this.self = self;
  }

  public boolean handleIncomingDataMessage(SignalServiceDataMessage message) throws IOException, VerificationFailedException {
    assert message.getGroupContext().isPresent();
    assert message.getGroupContext().get().getGroupV2().isPresent();
    SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();
    Group localState = storage.get(group);

    if (localState != null && localState.revision == group.getRevision()) {
      return false;
    }

    GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(group.getMasterKey());
    try {
      getGroup(groupSecretParams, group.getRevision());
    } catch (InvalidGroupStateException e) {
      // do nothing for now
      e.printStackTrace();
    }
    return true;
  }

  public Pair<SignalServiceDataMessage.Builder, Group> updateTitle(String groupID, String title) throws IOException, VerificationFailedException {
    Group group = storage.get(groupID);
    GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(group.getMasterKey());
    GroupsV2Operations.GroupOperations groupOperations = GroupsUtil.GetGroupsV2Operations(Manager.serviceConfiguration).forGroup(groupSecretParams);
    GroupChange.Actions.Builder change = groupOperations.createModifyGroupTitle(title);
    change.setSourceUuid(UuidUtil.toByteString(self));
    Pair<DecryptedGroup, GroupChange> groupChangePair = commitChange(group, change);
    group.group = groupChangePair.first();
    group.revision += 1;

    GroupMasterKey masterKey = group.getMasterKey();
    byte[] signedChange = groupChangePair.second().toByteArray();

    SignalServiceGroupV2.Builder groupBuilder = SignalServiceGroupV2.newBuilder(masterKey).withRevision(group.revision).withSignedGroupChange(signedChange);
    SignalServiceDataMessage.Builder updateMessage = SignalServiceDataMessage.newBuilder().asGroupMessage(groupBuilder.build());
    return new Pair<>(updateMessage, group);
  }

  public Pair<SignalServiceDataMessage.Builder, Group> updateAvatar(String groupID, String avatar) throws IOException, VerificationFailedException {
    Group group = storage.get(groupID);
    GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(group.getMasterKey());
    final byte[] avatarBytes = readAvatarBytes(avatar);
    String avatarCdnKey = groupsV2Api.uploadAvatar(avatarBytes, groupSecretParams, getAuthorizationForToday(groupSecretParams));
    GroupChange.Actions.Builder change = GroupChange.Actions.newBuilder().setModifyAvatar(GroupChange.Actions.ModifyAvatarAction.newBuilder().setAvatar(avatarCdnKey));

    change.setSourceUuid(UuidUtil.toByteString(self));
    Pair<DecryptedGroup, GroupChange> groupChangePair = commitChange(group, change);
    group.group = groupChangePair.first();
    group.revision += 1;

    GroupMasterKey masterKey = group.getMasterKey();
    byte[] signedChange = groupChangePair.second().toByteArray();

    SignalServiceGroupV2.Builder groupBuilder = SignalServiceGroupV2.newBuilder(masterKey).withRevision(group.revision).withSignedGroupChange(signedChange);
    SignalServiceDataMessage.Builder updateMessage = SignalServiceDataMessage.newBuilder().asGroupMessage(groupBuilder.build());
    return new Pair<>(updateMessage, group);
  }

  private byte[] readAvatarBytes(final String avatarFile) throws IOException {
    final byte[] avatarBytes;
    try (InputStream avatar = avatarFile == null ? null : new FileInputStream(avatarFile)) {
      if (avatar == null) {
        return null;
      }
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      Util.copy(avatar, byteArrayOutputStream);
      avatarBytes = byteArrayOutputStream.toByteArray();
    }
    return avatarBytes;
  }

  public Pair<SignalServiceDataMessage.Builder, Group> addMembers(String groupID, List<ProfileAndCredentialEntry> members) throws IOException, VerificationFailedException {
    Group group = storage.get(groupID);
    GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(group.getMasterKey());
    GroupsV2Operations.GroupOperations groupOperations = GroupsUtil.GetGroupsV2Operations(Manager.serviceConfiguration).forGroup(groupSecretParams);
    Set<GroupCandidate> candidates = new HashSet<>();
    for (ProfileAndCredentialEntry profileAndCredentialEntry : members) {
      Optional<ProfileKeyCredential> profileKeyCredential = Optional.absent();
      if (profileAndCredentialEntry != null) {
        profileKeyCredential = Optional.fromNullable(profileAndCredentialEntry.getProfileKeyCredential());
      }
      if (!profileAndCredentialEntry.getServiceAddress().getUuid().isPresent()) {
        logger.warn("cannot add member to group because we do not know their UUID!", new JsonAddress(profileAndCredentialEntry.getServiceAddress()).toRedactedString());
      }
      UUID uuid = profileAndCredentialEntry.getServiceAddress().getUuid().get();
      candidates.add(new GroupCandidate(uuid, profileKeyCredential));
    }
    GroupChange.Actions.Builder change = groupOperations.createModifyGroupMembershipChange(candidates, self);

    change.setSourceUuid(UuidUtil.toByteString(self));
    Pair<DecryptedGroup, GroupChange> groupChangePair = commitChange(group, change);
    group.group = groupChangePair.first();
    group.revision += 1;

    GroupMasterKey masterKey = group.getMasterKey();
    byte[] signedChange = groupChangePair.second().toByteArray();

    SignalServiceGroupV2.Builder groupBuilder = SignalServiceGroupV2.newBuilder(masterKey).withRevision(group.revision).withSignedGroupChange(signedChange);
    SignalServiceDataMessage.Builder updateMessage = SignalServiceDataMessage.newBuilder().asGroupMessage(groupBuilder.build());
    return new Pair<>(updateMessage, group);
  }

  public Pair<SignalServiceDataMessage.Builder, Group> removeMembers(String groupID, Set<UUID> members) throws IOException, VerificationFailedException {
    Group group = storage.get(groupID);
    GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(group.getMasterKey());
    GroupsV2Operations.GroupOperations groupOperations = GroupsUtil.GetGroupsV2Operations(Manager.serviceConfiguration).forGroup(groupSecretParams);
    GroupChange.Actions.Builder change = groupOperations.createRemoveMembersChange(members);
    change.setSourceUuid(UuidUtil.toByteString(self));
    Pair<DecryptedGroup, GroupChange> groupChangePair = commitChange(group, change);
    group.group = groupChangePair.first();
    group.revision += 1;

    GroupMasterKey masterKey = group.getMasterKey();
    byte[] signedChange = groupChangePair.second().toByteArray();

    SignalServiceGroupV2.Builder groupBuilder = SignalServiceGroupV2.newBuilder(masterKey).withRevision(group.revision).withSignedGroupChange(signedChange);
    SignalServiceDataMessage.Builder updateMessage = SignalServiceDataMessage.newBuilder().asGroupMessage(groupBuilder.build());
    return new Pair<>(updateMessage, group);
  }

  public JsonGroupJoinInfo getGroupJoinInfo(String urlString) throws IOException, InvalidInputException, VerificationFailedException, GroupLinkNotActiveException {
    URI uri;
    try {
      uri = new URI(urlString);
    } catch (URISyntaxException e) {
      return null;
    }
    String encoding = uri.getFragment();
    if (encoding == null || encoding.length() == 0) {
      return null;
    }
    byte[] bytes = Base64UrlSafe.decodePaddingAgnostic(encoding);
    GroupInviteLink groupInviteLink = GroupInviteLink.parseFrom(bytes);
    GroupInviteLink.GroupInviteLinkContentsV1 groupInviteLinkContentsV1 = groupInviteLink.getV1Contents();
    GroupMasterKey groupMasterKey = new GroupMasterKey(groupInviteLinkContentsV1.getGroupMasterKey().toByteArray());
    GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey);
    DecryptedGroupJoinInfo decryptedGroupJoinInfo = getGroupJoinInfo(groupSecretParams, groupInviteLinkContentsV1.getInviteLinkPassword().toByteArray());
    return new JsonGroupJoinInfo(decryptedGroupJoinInfo, groupMasterKey);
  }

  public DecryptedGroupJoinInfo getGroupJoinInfo(GroupSecretParams groupSecretParams, byte[] password)
      throws IOException, VerificationFailedException, GroupLinkNotActiveException {
    return groupsV2Api.getGroupJoinInfo(groupSecretParams, Optional.of(password), getAuthorizationForToday(groupSecretParams));
  }

  public GroupChange commitJoinChangeWithConflictResolution(int currentRevision, GroupChange.Actions.Builder change, GroupSecretParams groupSecretParams, byte[] password)
      throws IOException, GroupLinkNotActiveException, VerificationFailedException {
    for (int attempt = 0; attempt < 5; attempt++) {
      try {
        GroupChange.Actions changeActions = change.setRevision(currentRevision + 1).build();
        GroupChange signedGroupChange = commitJoinToServer(changeActions, groupSecretParams, password);

        return signedGroupChange;
      } catch (ConflictException e) {
        currentRevision = getGroupJoinInfo(groupSecretParams, password).getRevision();
      }
    }
    return null;
  }

  public GroupChange commitJoinToServer(GroupChange.Actions change, GroupSecretParams groupSecretParams, byte[] password) throws IOException, VerificationFailedException {
    int today = (int)TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
    AuthCredentialResponse authCredentialResponse = storage.getAuthCredential(groupsV2Api, today);
    GroupsV2AuthorizationString authorizationString = groupsV2Api.getGroupsV2AuthorizationString(self, today, groupSecretParams, authCredentialResponse);
    return groupsV2Api.patchGroup(change, authorizationString, Optional.fromNullable(password));
  }
  public Group getGroup(String groupID) throws VerificationFailedException, InvalidGroupStateException, IOException { return getGroup(groupID, -1); }
  public Group getGroup(String groupID, int revision) throws VerificationFailedException, InvalidGroupStateException, IOException {
    Group g = storage.get(groupID);
    GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(g.masterKey);
    return getGroup(groupSecretParams, revision);
  }

  public Group getGroup(GroupSecretParams groupSecretParams, int revision) throws InvalidGroupStateException, VerificationFailedException, IOException {
    String groupID = Base64.encodeBytes(groupSecretParams.getPublicParams().getGroupIdentifier().serialize());
    Group group = storage.get(groupID);
    if (group == null || group.revision < revision || revision < 0) {
      int today = (int)TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
      AuthCredentialResponse authCredential = storage.getAuthCredential(groupsV2Api, today);
      GroupsV2AuthorizationString authorization = groupsV2Api.getGroupsV2AuthorizationString(self, today, groupSecretParams, authCredential);
      try {
        DecryptedGroup decryptedGroup = groupsV2Api.getGroup(groupSecretParams, authorization);
        group = new Group(groupSecretParams.getMasterKey(), decryptedGroup.getRevision(), decryptedGroup);
        storage.update(group);
      } catch (NotInGroupException e) {
        if (group != null) {
          storage.remove(group);
        }
      }
    }
    return group;
  }

  public Pair<DecryptedGroup, GroupChange> commitChange(Group group, GroupChange.Actions.Builder change) throws IOException, VerificationFailedException {
    final GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(group.getMasterKey());
    final GroupsV2Operations.GroupOperations groupOperations = groupsV2Operations.forGroup(groupSecretParams);
    final DecryptedGroup previousGroupState = group.getGroup();
    final int nextRevision = previousGroupState.getRevision() + 1;
    final GroupChange.Actions changeActions = change.setRevision(nextRevision).build();
    final DecryptedGroupChange decryptedChange;
    final DecryptedGroup decryptedGroupState;

    try {
      decryptedChange = groupOperations.decryptChange(changeActions, self);
      decryptedGroupState = DecryptedGroupUtil.apply(previousGroupState, decryptedChange);
    } catch (VerificationFailedException | InvalidGroupStateException | NotAbleToApplyGroupV2ChangeException e) {
      throw new IOException(e);
    }

    int today = (int)TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
    AuthCredentialResponse authCredential = storage.getAuthCredential(groupsV2Api, today);
    GroupsV2AuthorizationString authString = groupsV2Api.getGroupsV2AuthorizationString(self, today, groupSecretParams, authCredential);
    GroupChange signedGroupChange = groupsV2Api.patchGroup(changeActions, authString, Optional.absent());

    return new Pair<>(decryptedGroupState, signedGroupChange);
  }

  private GroupsV2AuthorizationString getAuthorizationForToday(GroupSecretParams groupSecretParams) throws IOException, VerificationFailedException {
    int today = (int)TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
    AuthCredentialResponse authCredential = storage.getAuthCredential(groupsV2Api, today);
    return groupsV2Api.getGroupsV2AuthorizationString(self, today, groupSecretParams, authCredential);
  }
}
