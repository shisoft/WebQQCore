/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package iqq.im.action;

import iqq.im.QQActionListener;
import iqq.im.QQException;
import iqq.im.core.QQConstants;
import iqq.im.core.QQContext;
import iqq.im.core.QQSession;
import iqq.im.event.QQActionEvent;
import iqq.im.http.QQHttpRequest;
import iqq.im.http.QQHttpResponse;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author shisoft
 */
public class SetLongNick extends AbstractHttpAction {

    String longNick;

    public SetLongNick(QQContext context, QQActionListener listener, String longNick) {
        super(context, listener);
        this.longNick = longNick;
    }

    @Override
    protected QQHttpRequest onBuildRequest() throws QQException, JSONException {
        QQSession session = getContext().getSession();
        QQHttpRequest req = createHttpRequest("POST", QQConstants.URL_SET_LONG_NICK);
        req.addPostValue("vfwebqq", session.getVfwebqq());
        req.addPostValue("nlk", longNick);
        return req;
    }

    @Override
    protected void onHttpStatusOK(QQHttpResponse response) throws QQException,
            JSONException {
        JSONObject json = new JSONObject(response.getResponseString());
        if (json.getInt("retcode") == 0) {
            notifyActionEvent(QQActionEvent.Type.EVT_OK, null);
        } else {
            notifyActionEvent(QQActionEvent.Type.EVT_ERROR, null);
        }
    }
}
