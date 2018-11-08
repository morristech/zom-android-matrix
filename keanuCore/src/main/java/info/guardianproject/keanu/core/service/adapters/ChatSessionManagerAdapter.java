/*
 * Copyright (C) 2007-2008 Esmertec AG. Copyright (C) 2007-2008 The Android Open
 * Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package info.guardianproject.keanu.core.service.adapters;

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import info.guardianproject.keanu.core.model.Address;
import info.guardianproject.keanu.core.model.ChatGroup;
import info.guardianproject.keanu.core.model.ChatGroupManager;
import info.guardianproject.keanu.core.model.ChatSession;
import info.guardianproject.keanu.core.model.ChatSessionListener;
import info.guardianproject.keanu.core.model.ChatSessionManager;
import info.guardianproject.keanu.core.model.Contact;
import info.guardianproject.keanu.core.model.GroupListener;
import info.guardianproject.keanu.core.model.ImConnection;
import info.guardianproject.keanu.core.model.ImErrorInfo;
import info.guardianproject.keanu.core.plugin.xmpp.XmppAddress;
import info.guardianproject.keanu.core.provider.Imps;
import info.guardianproject.keanu.core.service.IChatSession;
import info.guardianproject.keanu.core.service.IChatSessionListener;
import info.guardianproject.keanu.core.service.IChatSessionManager;


/** manages the chat sessions for a given protocol */
public class ChatSessionManagerAdapter extends IChatSessionManager.Stub {

    ImConnectionAdapter mConnection;
    ChatSessionListenerAdapter mSessionListenerAdapter;
    final RemoteCallbackList<IChatSessionListener> mRemoteListeners = new RemoteCallbackList<IChatSessionListener>();

    HashMap<String, ChatSessionAdapter> mActiveChatSessionAdapters;

    public ChatSessionManagerAdapter(ImConnectionAdapter connection) {

        mConnection = connection;
        ImConnection connAdaptee = connection.getAdaptee();

        connAdaptee.getChatSessionManager().setAdapter(this);

        mActiveChatSessionAdapters = new HashMap<String, ChatSessionAdapter>();
        mSessionListenerAdapter = new ChatSessionListenerAdapter();
        getChatSessionManager().addChatSessionListener(mSessionListenerAdapter);

       
    }

    public ChatGroupManager getChatGroupManager ()
    {
        if ((mConnection.getAdaptee().getCapability() & ImConnection.CAPABILITY_GROUP_CHAT) != 0) {
            ChatGroupManager groupManager = mConnection.getAdaptee().getChatGroupManager();
            groupManager.addGroupListener(new ChatGroupListenerAdapter());
            return groupManager;
        }
        else
            return null;
    }
    
    public ChatSessionManager getChatSessionManager() {
        return mConnection.getAdaptee().getChatSessionManager();
    }

    public IChatSession createChatSession(String contactAddress, boolean isNewSession) {

        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection
                .getContactListManager();

        Contact contact = listManager.getContactByAddress(Address.stripResource(contactAddress));

        if (contact == null) {
            try {

               contact = new Contact (new XmppAddress(contactAddress),contactAddress, Imps.Contacts.TYPE_NORMAL);

               // long contactId = listManager.queryOrInsertContact(contact);
                
               // String[] address = {Address.stripResource(contactAddress)};
                //contact = listManager.createTemporaryContacts(address)[0];
               
            } catch (IllegalArgumentException e) {
                mSessionListenerAdapter.notifyChatSessionCreateFailed(contactAddress,
                        new ImErrorInfo(ImErrorInfo.ILLEGAL_CONTACT_ADDRESS,
                                "Invalid contact address:" + contactAddress));
                return null;
            }
        }

        if (contact != null) {
            ChatSession session = getChatSessionManager().createChatSession(contact, isNewSession);

            return getChatSessionAdapter(session, isNewSession);
        }
        else
            return null;
    }

    public IChatSession createMultiUserChatSession(String roomAddress, String subject, String nickname, boolean isNewChat)
    {

        ChatGroupManager groupMan = mConnection.getAdaptee().getChatGroupManager();

        try
        {
            if (roomAddress.endsWith("@"))
            {
                String confServer = groupMan.getDefaultGroupChatService();
                if (confServer != null)
                    roomAddress += confServer;
            }

            Address address = new XmppAddress(roomAddress); //TODO hard coding XMPP for now

            ChatGroup chatGroup = groupMan.getChatGroup(address);
            if (chatGroup == null)
                groupMan.createChatGroupAsync(roomAddress, subject, nickname);

            chatGroup = groupMan.getChatGroup(address);

            if (chatGroup != null)
                chatGroup.setName(subject);

            if (chatGroup != null && mConnection.getState() == ImConnection.LOGGED_IN)
            {
                ChatSession session = getChatSessionManager().createChatSession(chatGroup,isNewChat);
                ChatSessionAdapter adapter = getChatSessionAdapter(session, isNewChat);
                return adapter;
            }
            else
            {
                return null;
            }
        }
        catch (Exception e)
        {
            Log.e("Keanu","unable to join group chat" + e.getMessage());
            return null;
        }
    }

    public void closeChatSession(ChatSessionAdapter adapter) {
        synchronized (mActiveChatSessionAdapters) {
            ChatSession session = adapter.getAdaptee();
            getChatSessionManager().closeChatSession(session);

            String key = Address.stripResource(adapter.getAddress());
            mActiveChatSessionAdapters.remove(key);
        }
    }

    public void closeAllChatSessions() {
        synchronized (mActiveChatSessionAdapters) {
            ArrayList<ChatSessionAdapter> adapters = new ArrayList<ChatSessionAdapter>(
                    mActiveChatSessionAdapters.values());
            for (ChatSessionAdapter adapter : adapters) {
                ChatSession session = adapter.getAdaptee();
                getChatSessionManager().closeChatSession(session);

                String key = Address.stripResource(adapter.getAddress());
                mActiveChatSessionAdapters.remove(key);
            }
        }
    }

    public void updateChatSession(String oldAddress, ChatSessionAdapter adapter) {
        synchronized (mActiveChatSessionAdapters) {
            mActiveChatSessionAdapters.remove(oldAddress);
            mActiveChatSessionAdapters.put(Address.stripResource(adapter.getAddress()), adapter);
        }
    }

    public IChatSession getChatSession(String address) {
        synchronized (mActiveChatSessionAdapters) {
            return mActiveChatSessionAdapters.get(Address.stripResource(address));
        }
    }

    public List<IChatSession> getActiveChatSessions() {
        synchronized (mActiveChatSessionAdapters) {
            return new ArrayList<IChatSession>(mActiveChatSessionAdapters.values());
        }
    }

    public int getChatSessionCount() {
        synchronized (mActiveChatSessionAdapters) {
            return mActiveChatSessionAdapters.size();
        }
    }

    public void registerChatSessionListener(IChatSessionListener listener) {
        if (listener != null) {
            mRemoteListeners.register(listener);
        }
    }

    public void unregisterChatSessionListener(IChatSessionListener listener) {
        if (listener != null) {
            mRemoteListeners.unregister(listener);
        }
    }

    public synchronized ChatSessionAdapter getChatSessionAdapter(ChatSession session, boolean isNewSession) {

        Address participantAddress = session.getParticipant().getAddress();
        String key = Address.stripResource(participantAddress.getAddress());
        ChatSessionAdapter adapter = mActiveChatSessionAdapters.get(key);

        if (adapter == null) {
            adapter = new ChatSessionAdapter(session, session.getParticipant(), mConnection, isNewSession);
            mActiveChatSessionAdapters.put(key, adapter);
        }

        return adapter;
    }

    class ChatSessionListenerAdapter implements ChatSessionListener {

        public void onChatSessionCreated(ChatSession session) {
            final IChatSession sessionAdapter = getChatSessionAdapter(session, false);
            final int N = mRemoteListeners.beginBroadcast();
            if (N > 0) {
                for (int i = 0; i < N; i++) {
                    IChatSessionListener listener = mRemoteListeners.getBroadcastItem(i);
                    try {
                        listener.onChatSessionCreated(sessionAdapter);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
                mRemoteListeners.finishBroadcast();
            }
        }

        public void notifyChatSessionCreateFailed(final String name, final ImErrorInfo error) {
            final int N = mRemoteListeners.beginBroadcast();
            if (N > 0) {
                for (int i = 0; i < N; i++) {
                    IChatSessionListener listener = mRemoteListeners.getBroadcastItem(i);
                    try {
                        listener.onChatSessionCreateError(name, error);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
                mRemoteListeners.finishBroadcast();
            }
        }
    }

    class ChatGroupListenerAdapter implements GroupListener {
        public void onGroupCreated(ChatGroup group) {
        }

        public void onGroupDeleted(ChatGroup group) {
            closeSession(group);
        }

        public void onGroupError(int errorType, String name, ImErrorInfo error) {
            if (errorType == ERROR_CREATING_GROUP) {
                mSessionListenerAdapter.notifyChatSessionCreateFailed(name, error);
            }
        }

        public void onJoinedGroup(ChatGroup group) {
            getChatSessionManager().createChatSession(group,false);
        }

        public void onLeftGroup(ChatGroup group) {
            closeSession(group);
        }

        private void closeSession(ChatGroup group) {
            String address = group.getAddress().getAddress();
            IChatSession session = getChatSession(address);
            if (session != null) {
                closeChatSession((ChatSessionAdapter) session);
            }
        }
    }
}
