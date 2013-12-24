/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package net.shisoft.rmi.server.svr.plugin;

import iqq.im.QQActionListener;
import iqq.im.QQClient;
import iqq.im.QQException;
import iqq.im.WebQQClient;
import iqq.im.actor.ThreadActorDispatcher;
import iqq.im.bean.QQBuddy;
import iqq.im.bean.QQGroup;
import iqq.im.bean.QQMsg;
import iqq.im.bean.QQStatus;
import iqq.im.bean.QQUser;
import iqq.im.bean.content.ContentItem;
import iqq.im.bean.content.TextItem;
import iqq.im.event.QQActionEvent;
import iqq.im.event.QQNotifyEvent;
import iqq.im.event.QQNotifyEventArgs;
import iqq.im.event.QQNotifyHandler;
import iqq.im.event.QQNotifyHandlerProxy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.rmi.RemoteException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.shisoft.datasources.UniversalContactSource;
import net.shisoft.db.obj.UniversalContact;
import net.shisoft.providers.ContactAddressbook;
import net.shisoft.sdk.Helpers.SecureCodeHelper;
import net.shisoft.sdk.Logic.IM;
import net.shisoft.sdk.utils.ImageUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableShort;

/**
 *
 * @author shisoft
 */
public class ClassQQ extends IM { //UnicastRemoteObject

    private QQClient client;
    private final Map<Long, QQGroup> groups = new HashMap<>();

    @Override
    public Boolean Login(String account, String pass) throws RemoteException, InterruptedException {
        final MutableBoolean doneLogin = new MutableBoolean(false);
        final MutableBoolean loginSucceed = new MutableBoolean(false);
        final MutableShort infoGatheringProgress = new MutableShort(0);
        final QQActionListener listener = new QQActionListener() {
            @Override
            public void onActionEvent(QQActionEvent event) {
                doneLogin.setValue(true);
                System.out.println("LOGIN_STATUS:" + event.getType() + ":" + event.getTarget());
                if (event.getType() == QQActionEvent.Type.EVT_OK) {
                    loginSucceed.setValue(true);
                    //到这里就算是登录成功了

                    //获取下用户信息
                    client.getUserInfo(client.getAccount(), new QQActionListener() {
                        @Override
                        public void onActionEvent(QQActionEvent event) {
                            System.out.println("LOGIN_STATUS:" + event.getType() + ":" + event.getTarget());
                            infoGatheringProgress.add(1);
                        }
                    });

                    // 获取好友列表, 群列表等等..TODO.
                    // 不一定调用，可能会有本地缓存
                    client.getBuddyList(new QQActionListener() {
                        @Override
                        public void onActionEvent(QQActionEvent event) {
                            infoGatheringProgress.add(1);
                        }
                    });
                    //不一定调用，可能会有本地缓存

                    client.getGroupList(new QQActionListener() {
                        @Override
                        public void onActionEvent(QQActionEvent event) {
                            infoGatheringProgress.add(1);
                        }
                    });
                    client.getDiscuzList(new QQActionListener() {
                        @Override
                        public void onActionEvent(QQActionEvent event) {
                            infoGatheringProgress.add(1);
                        }
                    });
                    //所有的逻辑完了后，启动消息轮询
                    client.beginPollMsg();
                } else {
                    loginSucceed.setValue(false);
                }
            }
        };
        client = new WebQQClient(account, pass, new QQNotifyHandlerProxy(this), new ThreadActorDispatcher());
        client.login(QQStatus.ONLINE, listener);
        int counter = 0;
        while (!doneLogin.booleanValue()) {
            Thread.sleep(1000);
            counter++;
            if (counter > 60) {
                return false;
            }
        }
        if (loginSucceed.booleanValue()) {
            while (infoGatheringProgress.shortValue() < 4) {
                Thread.sleep(500);
            }
            this.listContacts();
        } else {
            return false;
        }
        return true;
    }

    @QQNotifyHandler(QQNotifyEvent.Type.CHAT_MSG)
    public void processBuddyMsg(final QQNotifyEvent event) throws Exception {
        final ClassQQ thi = this;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                QQMsg msg = (QQMsg) event.getTarget();
                UniversalContact from = Member2UC(msg.getFrom());
                switch (msg.getType()) {
                    case SESSION_MSG:
                    case BUDDY_MSG:
                        try {
                            //TakeOverMessage(String.valueOf(msg.getFrom().getUin()), msg.getText(), true);
                            ui.getIMHelper().TakeOverMessage(service, from.getSvrId(), msg.getText(), thi, true, msg.getDate());
                        } catch (Exception ex) {
                            Logger.getLogger(ClassQQ.class.getName()).log(Level.SEVERE, null, ex);
                        }
                        break;
                    case DISCUZ_MSG:
                    case GROUP_MSG:
                        UniversalContact group = Group2UC(msg.getGroup());
                        ui.getIMHelper().TakeOverGroupMessage(service, group, from, msg.getText(), true, msg.getDate(), null);
                        break;
                }
            }
        };
        this.se.getServiceThreadManager().Pool.addExecuteTask(r);
    }

    @QQNotifyHandler(QQNotifyEvent.Type.BUDDY_STATUS_CHANGE)
    public void processBuddyStatusChange(QQNotifyEvent event) throws QQException {
        QQBuddy buddy = (QQBuddy) event.getTarget();
        QQBuddy inRoster = client.getBuddyByUin(buddy.getUin());
        if (inRoster != null) {
            buddy = inRoster;
        }
        UniversalContact uc = Member2UC(buddy);
        SetContactStatusChanged(uc.getSvrId());
    }

    @QQNotifyHandler(QQNotifyEvent.Type.KICK_OFFLINE)
    protected void processKickOff(QQNotifyEvent event) {
        In = false;
    }

    @QQNotifyHandler(QQNotifyEvent.Type.CAPACHA_VERIFY)
    protected void processVerify(QQNotifyEvent event) throws IOException {
        QQNotifyEventArgs.ImageVerify verify = (QQNotifyEventArgs.ImageVerify) event.getTarget();
        BufferedImage bufferedImage = verify.image;
        String imgSrc = ImageUtils.BufferedImageToString(bufferedImage);
        String code = SecureCodeHelper.GetSecureCode(service.GetCode(), imgSrc, String.valueOf((new Random()).nextInt()), user);
        client.submitVerify(code, event);
    }

    @Override
    public void DoSetDisplayName(String name) {

    }

    @Override
    public boolean GetLoginInProgress() {
        return false;
    }

    @Override
    public void BoradcastText(final String text) throws RemoteException {
        client.setLongNick(text, new QQActionListener() {
            @Override
            public void onActionEvent(QQActionEvent event) {
                switch (event.getType()) {
                    case EVT_OK:
                        System.out.println("Set long nick to '" + text + "' success");
                        break;
                    case EVT_ERROR:
                        System.out.println("Set long nick to '" + text + "' failed");
                        break;
                }
            }
        });
    }

    @Override
    public Boolean DoAddNewContact(String Address) throws RemoteException {
        return false;
    }

    @Override
    public Boolean GetIsAddressOnline(String Address) throws RemoteException {

        return client.getBuddyByUin(Long.parseLong(Address)).getStatus() != QQStatus.OFFLINE;
    }

    @Override
    public Boolean DoSendMessage(String receiver, String text) throws RemoteException, InterruptedException {
        QQMsg msg = new QQMsg();
        Long receiverId;
        if (receiver.startsWith("g:")) {
            receiverId = Long.parseLong(receiver.replace("g:", ""));
            msg.setGroup(groups.get(receiverId));
            msg.setType(QQMsg.Type.GROUP_MSG);
        } else {
            receiverId = Long.parseLong(receiver);
            msg.setTo(client.getBuddyByUin(receiverId));
            msg.setType(QQMsg.Type.BUDDY_MSG);
        }
        msg.setFrom(client.getAccount());
        msg.setDate(new Date());
        List<ContentItem> contentItems = new ArrayList<>();
        contentItems.add(new TextItem(text));
        msg.setContentList(contentItems);
        final MutableShort sendSuccess = new MutableShort(-1);
        client.sendMsg(msg, new QQActionListener() {
            @Override
            public void onActionEvent(QQActionEvent actEvent) {
                switch (actEvent.getType()) {
                    case EVT_OK: {
                        sendSuccess.setValue(1);
                        break;
                    }
                    case EVT_ERROR: {
                        System.out.println("send msg Error" + (QQException) actEvent.getTarget());
                        sendSuccess.setValue(0);
                        break;
                    }
                }

            }
        });
//        try {
//            future.waitEvent();
//            return sendSuccess.shortValue() == 1;
//        } catch (Exception e) {
//            return false;
//        }

        // wait will deadlock the therad, have to return true with no concern
        return true;
    }

    @Override
    public Boolean getFireable() throws RemoteException {
        return In;
    }

    @Override
    public String getMyStaus() throws RemoteException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Boolean DeleteContact(String Address) throws RemoteException {
        return false;
    }

    @Override
    public Set<UniversalContact> GetContacts(Set<UniversalContact> ca) {
        for (QQBuddy buddy : client.getBuddyList()) {
            UniversalContact uc = Member2UC(buddy);
            uc.setGroupName(buddy.getCategory().getName());
            ca.add(uc);
        }
        for (QQGroup group : client.getGroupList()) {
            UniversalContact uc = Group2UC(group);
            ca.add(uc);
            groups.put(group.getGin(), group);
        }
        return ca;
    }

    @Override
    public void getServiceAccountUC() throws RemoteException {
        this.setAccountUC(Member2UC(client.getAccount()));
    }

    private UniversalContact Group2UC(QQGroup group) {
        final UniversalContact uc = new UniversalContact();
        if (group.getFace() != null) {
            uc.setAvatar(ImageUtils.BufferedImageToString(group.getFace()));
        }
        uc.setDispName(group.getName());
        uc.setGroupName("QQ 群");
        uc.setScrName(String.valueOf(group.getGid()));
        uc.setSvr(this.service.GetCode());
        uc.setSvrId("g:" + String.valueOf(group.getGin()));

        if (checkAvatarRequired(uc)) {
            client.getGroupFace(group, new QQActionListener() {
                @Override
                public void onActionEvent(QQActionEvent event) {
                    if (event.getType() == QQActionEvent.Type.EVT_OK) {
                        UniversalContact dbuc = UniversalContactSource.getContactBySvrID(service.GetCode(), uc.getSvrId());
                        if (dbuc == null) {
                            dbuc = uc;
                        }
                        if (StringUtils.trimToNull(dbuc.getAvatar()) == null) {
                            QQGroup m = (QQGroup) event.getTarget();
                            dbuc.setAvatar(ImageUtils.BufferedImageToString(m.getFace()));
                            if (dbuc.getAvatar() != null) {
                                UniversalContactSource.saveAndGet(dbuc);
                            }
                        }
                    }
                }
            });
        }

        return uc;
    }

    private UniversalContact Member2UC(QQUser m) {
        final UniversalContact uc = new UniversalContact();
        if (m.getFace() != null) {
            uc.setAvatar(ImageUtils.BufferedImageToString(m.getFace()));
        }
        uc.setDispName(m.getNickname());
        uc.setSvr(this.service.GetCode());
        uc.setSvrId(String.valueOf(m.getUin()));
        if (m.getQQ() > 0) {
            uc.setScrName(String.valueOf(m.getQQ()));
        }
        ContactAddressbook cab = new ContactAddressbook();
        //cab.AddEntry("bio", m.getLnick());
        cab.AddEntry("gender", m.getGender());
        cab.AddEntry("birth", m.getBirthday() == null ? null : DateFormat.getDateInstance().format(m.getBirthday()));
        cab.AddEntry("phone", m.getPhone());
        cab.AddEntry("mobile", m.getMobile());
        cab.AddEntry("mail", m.getEmail());
        cab.AddEntry("college", m.getCollege());
        cab.AddEntry("homepage", m.getHomepage());
        cab.AddEntry("country", m.getCountry());
        cab.AddEntry("province", m.getProvince());
        cab.AddEntry("city", m.getCity());
        cab.AddEntry("bio", m.getPersonal());
        cab.AddEntry("college", m.getCollege());
        uc.setContactAddressbook(cab);
        if (checkAvatarRequired(uc)) {
            client.getUserFace(m, new QQActionListener() {
                @Override
                public void onActionEvent(QQActionEvent event) {
                    if (event.getType() == QQActionEvent.Type.EVT_OK) {
                        UniversalContact dbuc = UniversalContactSource.getContactBySvrID(service.GetCode(), uc.getSvrId());
                        if (dbuc == null) {
                            dbuc = uc;
                        }
                        if (StringUtils.trimToNull(dbuc.getAvatar()) == null) {
                            try {
                                QQUser m = (QQUser) event.getTarget();
                                dbuc.setAvatar(ImageUtils.BufferedImageToString(m.getFace()));
                                if (dbuc.getAvatar() != null) {
                                    UniversalContactSource.saveAndGet(dbuc);
                                }
                            } catch (Exception e) {

                            }
                        }
                    }
                }
            });
        }
        return UniversalContactSource.saveAndGet(uc);
    }

    private boolean checkAvatarRequired(UniversalContact uc) {
        if (StringUtils.trimToNull(uc.getAvatar()) == null) {
            UniversalContact dbuc = UniversalContactSource.getContactBySvrID(service.GetCode(), uc.getSvrId());
            if (dbuc == null ? true : StringUtils.trimToNull(dbuc.getAvatar()) == null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void Disconnect() throws RemoteException {
        client.destroy();
    }
}
