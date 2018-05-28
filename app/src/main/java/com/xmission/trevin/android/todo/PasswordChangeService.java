/*
 * $Id: PasswordChangeService.java,v 1.2 2014/03/22 19:03:52 trevin Exp trevin $
 * Copyright © 2011 Trevin Beattie
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
 *
 * $Log: PasswordChangeService.java,v $
 * Revision 1.2  2014/03/22 19:03:52  trevin
 * Added the copyright notice.
 * Changed the password intents from String to char[]
 *   to prevent Java from caching the values.
 * Pop up a bad password message if the old password is incorrect.
 * Before updating an entry with encrypted values, make sure the
 *   ContentValues object actually contains byte arrays.
 *   (During debugging, these were mysteriously changed to char arrays.)
 * Fixed a bug in holding and releasing global encryption;
 *   references to self are not valid for a service.
 *
 * Revision 1.1  2011/05/12 05:17:07  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.todo;

import java.security.GeneralSecurityException;

import com.xmission.trevin.android.todo.ToDo.ToDoItem;

import android.app.IntentService;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.widget.Toast;

/**
 * Encrypts and decrypts private entries in the database
 * when the user sets or changes the password.
 *
 * @author Trevin Beattie
 */
public class PasswordChangeService extends IntentService
	implements ProgressReportingService {

    private static final String TAG = "PasswordChangeService";

    /** The name of the Intent action for changing the password */
    public static final String ACTION_CHANGE_PASSWORD =
	"com.xmission.trevin.android.todo.ChangePassword";
    /**
     * The name of the Intent extra data that holds the old password.
     * This must be a char array!
     */
    public static final String EXTRA_OLD_PASSWORD =
	"com.xmission.trevin.android.todo.OldPassword";
    /**
     * The name of the Intent extra data that holds the new password.
     * This must be a char array!
     */
    public static final String EXTRA_NEW_PASSWORD =
	"com.xmission.trevin.android.todo.NewPassword";

    /**
     * The columns we are interested in from the item table
     */
    private static final String[] ITEM_PROJECTION = new String[] {
            ToDoItem._ID,
            ToDoItem.DESCRIPTION,
            ToDoItem.NOTE,
            ToDoItem.PRIVATE,
    };

    /** The current mode of operation */
    public enum OpMode {
	DECRYPTING, ENCRYPTING
    };
    private OpMode currentMode = OpMode.DECRYPTING;

    /** The current number of entries changed */
    private int numChanged = 0;

    /** The total number of entries to be changed */
    private int changeTarget = 1;

    public class PasswordBinder extends Binder {
	PasswordChangeService getService() {
	    Log.d(TAG, "PasswordBinder.getService()");
	    return PasswordChangeService.this;
	}
    }

    private PasswordBinder binder = new PasswordBinder();

    /** Create the importer service with a named worker thread */
    public PasswordChangeService() {
	super(PasswordChangeService.class.getSimpleName());
	Log.d(TAG,"created");
	// If we die in the middle of a password change, restart the request.
	setIntentRedelivery(true);
    }

    /** @return the current mode of operation */
    public String getCurrentMode() {
	switch (currentMode) {
	case DECRYPTING:
	    return getString(R.string.ProgressMessageDecrypting);
	case ENCRYPTING:
	    return getString(R.string.ProgressMessageEncrypting);
	default:
	    return "";
	}
    }

    /** @return the total number of entries to be changed */
    public int getMaxCount() { return changeTarget; }

    /** @return the number of entries changed so far */
    public int getChangedCount() { return numChanged; }

    static final java.util.Random RANDOM = new java.util.Random();

    /** Called when an activity requests a password change */
    @Override
    protected void onHandleIntent(Intent intent) {
	Log.d(TAG, ".onHandleIntent({action=" + intent.getAction()
		+ ", data=" + intent.getDataString() + ")}");
	// Get the old password, if there is one.
	char[] oldPassword = intent.getCharArrayExtra(EXTRA_OLD_PASSWORD);
	// Get the new password, if there is one.
	char[] newPassword = intent.getCharArrayExtra(EXTRA_NEW_PASSWORD);
	Cursor c = null;
	ContentResolver resolver = getContentResolver();
	int decrypTotal = 0;
	StringEncryption globalEncryption =
	    StringEncryption.holdGlobalEncryption();
	try {
	    StringEncryption encryptor = new StringEncryption();
	    if (oldPassword != null) {
		if (!encryptor.hasPassword(resolver)) {
		    Toast.makeText(this, R.string.ToastBadPassword,
			    Toast.LENGTH_LONG);
		    return;
		}
		encryptor.setPassword(oldPassword);
		if (!encryptor.checkPassword(resolver)) {
		    Toast.makeText(this, R.string.ToastBadPassword,
			    Toast.LENGTH_LONG);
		    return;
		}
		// Decrypt all entries
		c = resolver.query(
			ToDoItem.CONTENT_URI, ITEM_PROJECTION,
			ToDoItem.PRIVATE + " > 1", null, null);
		decrypTotal = c.getCount();
		Log.d(TAG, ".onHandleIntent: Decrypting "
			+ decrypTotal + " items");
		// For now, just estimate the amount of work to be done.
		changeTarget = (newPassword == null) ?
			decrypTotal : (decrypTotal * 2);
		while (c.moveToNext()) {
		    ContentValues values = new ContentValues();
		    Uri itemUri = Uri.withAppendedPath(
			    ToDoItem.CONTENT_URI,
			    Integer.toString(c.getInt(
				    c.getColumnIndex(ToDoItem._ID))));
		    values.put(ToDoItem.DESCRIPTION,
			    encryptor.decrypt(c.getBlob(
				    c.getColumnIndex(
					    ToDoItem.DESCRIPTION))));
		    if (!c.isNull(c.getColumnIndex(ToDoItem.NOTE)))
			values.put(ToDoItem.NOTE,
				encryptor.decrypt(c.getBlob(
					c.getColumnIndex(ToDoItem.NOTE))));
		    values.put(ToDoItem.PRIVATE, 1);
		    resolver.update(itemUri, values, null, null);
		    numChanged++;
		    Log.d(TAG, ".onHandleIntent: decrypted row " + numChanged);
		}
		c.close();
		c = null;
		Log.d(TAG, ".onHandleIntent: Removing the old password hash");
		encryptor.removePassword(resolver);

		// Forget the old password
		encryptor.forgetPassword();
	    } else {
		if (encryptor.hasPassword(resolver)) {
		    Toast.makeText(this, R.string.ToastBadPassword,
			    Toast.LENGTH_LONG);
		    return;
		}
	    }

	    if (newPassword != null) {
		currentMode = OpMode.ENCRYPTING;
		Log.d(TAG, ".onHandleIntent: Storing the new password hash");
		encryptor.setPassword(newPassword);
		// Set the new password
		encryptor.storePassword(resolver);

		// Encrypt all entries
		c = resolver.query(ToDoItem.CONTENT_URI, ITEM_PROJECTION,
			ToDoItem.PRIVATE + " = 1", null, null);
		changeTarget = decrypTotal + c.getCount();
		Log.d(TAG, ".onHandleIntent: Encrypting "
			+ c.getCount() + " items");
		while (c.moveToNext()) {
		    ContentValues values = new ContentValues();
		    Uri itemUri = Uri.withAppendedPath(
				ToDoItem.CONTENT_URI,
				Integer.toString(c.getInt(
					c.getColumnIndex(ToDoItem._ID))));
		    values.put(ToDoItem.DESCRIPTION,
			    encryptor.encrypt(c.getString(
				    c.getColumnIndex(
					    ToDoItem.DESCRIPTION))));
		    if (!c.isNull(c.getColumnIndex(ToDoItem.NOTE)))
			values.put(ToDoItem.NOTE,
				encryptor.encrypt(c.getString(
					c.getColumnIndex(ToDoItem.NOTE))));
		    values.put(ToDoItem.PRIVATE, 2);
		    // Verify the data types — there have been problems with this
		    if (!(values.get(ToDoItem.DESCRIPTION) instanceof byte[]) ||
			    (values.containsKey(ToDoItem.NOTE) &&
				    !(values.get(ToDoItem.NOTE) instanceof byte[]))) {
			Log.e(TAG, "Error storing encrypted description: expected byte[], got "
				+ values.get(ToDoItem.DESCRIPTION).getClass().getSimpleName());
		    } else {
			resolver.update(itemUri, values, null, null);
		    }
		    numChanged++;
		    Log.d(TAG, ".onHandleIntent: encrypted row "
			    + (numChanged - decrypTotal));
		}
		if (globalEncryption.hasKey()) {
		    globalEncryption.setPassword(newPassword);
		    globalEncryption.checkPassword(resolver);
		}
	    } else {
		if (globalEncryption.hasKey())
		    globalEncryption.forgetPassword();
	    }

	} catch (GeneralSecurityException gsx) {
	    if (c != null)
		c.close();
	    Log.e(TAG, "Error changing the password!", gsx);
	    Toast.makeText(this, gsx.getMessage(), Toast.LENGTH_LONG);
	} finally {
	    StringEncryption.releaseGlobalEncryption();
	}
    }

    /**
     * Called when the service is created.
     */
    @Override
    public void onCreate() {
        Log.d(TAG, ".onCreate");
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, ".onBind");
	return binder;
    }

}
