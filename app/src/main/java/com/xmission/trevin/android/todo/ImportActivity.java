/*
 * $Id: ImportActivity.java,v 1.1 2014/03/22 19:46:28 trevin Exp trevin $
 * Copyright © 2013 Trevin Beattie
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
 * $Log: ImportActivity.java,v $
 * Revision 1.1  2014/03/22 19:46:28  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.todo;

import java.io.File;


import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.util.Log;
import android.view.View;
import android.widget.*;

/**
 * Displays options for importing a previous backup of the To Do list,
 * prior to actually attempting the import.
 *
 * @author Trevin Beattie
 */
public class ImportActivity extends Activity {

    private static final String TAG = "ImportActivity";

    /** The file name */
    EditText importFileName = null;

    /** Import type spinner */
    Spinner importTypeList = null;

    /** Checkbox for including private records */
    CheckBox importPrivateCheckBox = null;

    /** Password for the imported file */
    EditText importPassword = null;

    /** Checkbox for revealing the password */
    CheckBox showPasswordCheckBox = null;

    /**
     * Rows for the fields related to private records;
     * the warning about the password not being set
     * will be the first item.
     */
    TableRow[] passwordFieldRows = new TableRow[3];

    /** Import button */
    Button importButton = null;

    /**
     * Cancel button; this should remain available
     * until any changes are made to the current database.
     */
    Button cancelButton = null;

    /** Progress bar */
    ProgressBar importProgressBar = null;

    /** Progress message */
    TextView importProgressMessage = null;

    /** Progress reporting service */
    ProgressReportingService progressService = null;

    /** Shared preferences */
    private SharedPreferences prefs;

    /** Label for the last imported file name */
    public static final String TPREF_IMPORT_FILE = "ImportFile";

    /** Label for the preferences option "Import type" */
    public static final String TPREF_IMPORT_TYPE = "ImportType";

    /** Label for the preferences option "Include Private" */
    public static final String TPREF_IMPORT_PRIVATE = "ImportPrivate";

    StringEncryption encryptor;

    /**
     * Map of entries in the Import Type spinner
     * to import types used by the XMLImporterService
     */
    private static final XMLImporterService.ImportType[] xmlImportTypes = {
	XMLImporterService.ImportType.CLEAN,
	XMLImporterService.ImportType.REVERT,
	XMLImporterService.ImportType.UPDATE,
	XMLImporterService.ImportType.MERGE,
	XMLImporterService.ImportType.ADD,
	XMLImporterService.ImportType.TEST,
    };

    /**
     * Map of entries in the Import Type spinner
     * to import types used by the PalmImporterService
     */
    private static final PalmImporterService.ImportType[] palmImportTypes = {
	PalmImporterService.ImportType.CLEAN,
	PalmImporterService.ImportType.OVERWRITE,
	null,	// The Palm imported doesn't have "update"
	PalmImporterService.ImportType.MERGE,
	PalmImporterService.ImportType.ADD,
	PalmImporterService.ImportType.TEST,
    };

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, ".onCreate");

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        // Inflate our view so we can find our fields
	setContentView(R.layout.import_options);

	importFileName = (EditText) findViewById(R.id.ImportEditTextFile);
	importTypeList = (Spinner) findViewById(R.id.ImportSpinnerImportType);
	importPrivateCheckBox = (CheckBox) findViewById(
		R.id.ImportCheckBoxIncludePrivate);
	passwordFieldRows[0] = (TableRow) findViewById(
		R.id.TableRowPasswordNotSetWarning);
	importPassword = (EditText) findViewById(R.id.ImportEditTextPassword);
	passwordFieldRows[1] = (TableRow) findViewById(
		R.id.TableRowPassword);
	showPasswordCheckBox = (CheckBox) findViewById(
		R.id.ImportCheckBoxShowPassword);
	passwordFieldRows[2] = (TableRow) findViewById(
		R.id.TableRowShowPassword);
	importButton = (Button) findViewById(R.id.ImportButtonOK);
	cancelButton = (Button) findViewById(R.id.ImportButtonCancel);
	importProgressBar = (ProgressBar) findViewById(R.id.ImportProgressBar);
	importProgressMessage = (TextView) findViewById(
		R.id.ImportTextProgressMessage);

	ArrayAdapter<CharSequence> importTypeAdapter =
	    ArrayAdapter.createFromResource(this, R.array.ImportTypeList,
		    R.layout.simple_spinner_dropdown_item);
	importTypeAdapter.setDropDownViewResource(
		R.layout.simple_spinner_dropdown_item);
	importTypeList.setAdapter(importTypeAdapter);

	encryptor = StringEncryption.holdGlobalEncryption();
	prefs = getSharedPreferences(
		ToDoListActivity.TODO_PREFERENCES, MODE_PRIVATE);

	// Set default values
	String fileName = Environment.getExternalStorageDirectory()
		    + "/Android/Data/"
		    + ToDoListActivity.class.getPackage().getName()
		    + "/todo.xml";
	fileName = prefs.getString(TPREF_IMPORT_FILE, fileName);
	importFileName.setText(fileName);

	int importTypeIndex = prefs.getInt(TPREF_IMPORT_TYPE, 2);	// update
	importTypeList.setSelection(importTypeIndex);

	boolean importPrivate = prefs.getBoolean(TPREF_IMPORT_PRIVATE, true);
	importPrivateCheckBox.setChecked(importPrivate);
	for (int i = 1; i < passwordFieldRows.length; i++)
	    passwordFieldRows[i].setVisibility(
		    importPrivate ? View.VISIBLE : View.GONE);

	char[] currentPassword = encryptor.getPassword();
	passwordFieldRows[0].setVisibility(importPrivate &&
		(currentPassword == null) ? View.VISIBLE : View.GONE);
	if (currentPassword == null)
	    currentPassword = new char[0];
	importPassword.setText(currentPassword, 0, currentPassword.length);

	// At least until we know how big the input file is...
	importProgressBar.setIndeterminate(true);
	importProgressBar.setVisibility(View.GONE);

	// Set callbacks
	importFileName.addTextChangedListener(new TextWatcher () {
	    @Override
	    public void afterTextChanged(Editable s) {
		prefs.edit().putString(TPREF_IMPORT_FILE, s.toString()).commit();
	    }
	    @Override
	    public void beforeTextChanged(CharSequence s,
		    int start, int count, int after) {}
	    @Override
	    public void onTextChanged(CharSequence s,
		    int start, int before, int count) {}
	});

	importTypeList.setOnItemSelectedListener(
		new AdapterView.OnItemSelectedListener() {
		    @Override
		    public void onNothingSelected(AdapterView<?> parent) {
			// Do nothing
		    }
		    @Override
		    public void onItemSelected(AdapterView<?> parent, View child,
			    int position, long id) {
			Log.d(TAG, "importTypeList.onItemSelected(" + position + ")");
			prefs.edit().putInt(TPREF_IMPORT_TYPE, position).commit();
		    }
		});

	importPrivateCheckBox.setOnCheckedChangeListener(
		new CompoundButton.OnCheckedChangeListener() {
		    public void onCheckedChanged(
			    CompoundButton b, boolean checked) {
			prefs.edit().putBoolean(
				TPREF_IMPORT_PRIVATE, checked).commit();
			passwordFieldRows[0].setVisibility(checked &&
				(encryptor.getPassword() == null)
				? View.VISIBLE : View.GONE);
			for (int i = 1; i < passwordFieldRows.length; i++)
			    passwordFieldRows[i].setVisibility(
				    checked ? View.VISIBLE : View.GONE);
		    }
		});

	showPasswordCheckBox.setOnCheckedChangeListener(
		new CompoundButton.OnCheckedChangeListener() {
		    public void onCheckedChanged(
			    CompoundButton b, boolean checked) {
			int oldType = importPassword.getInputType();
			if (checked)
			    oldType &= ~InputType.TYPE_TEXT_VARIATION_PASSWORD;
			else
			    oldType |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
			importPassword.setInputType(oldType);
		    }
		});

	importButton.setOnClickListener(new ImportButtonOnClickListener());
	cancelButton.setOnClickListener(
		new View.OnClickListener() {
		    @Override
		    public void onClick(View v) {
			Log.d(TAG, "ImportButtonCancel.onClick");
			ImportActivity.this.finish();
		    }
		});
    }

    /** Called when the activity is about to be destroyed */
    @Override
    public void onDestroy() {
	StringEncryption.releaseGlobalEncryption(this);
	super.onDestroy();
    }

    /**
     * Override the back button to prevent it from happening
     * in the middle of an import.
     */
    @Override
    public void onBackPressed() {
	if (cancelButton.isEnabled())
	    super.onBackPressed();
    }

    /** Enable or disable the form items */
    private void xableFormElements(boolean enable) {
	importFileName.setEnabled(enable);
	importTypeList.setEnabled(enable);
	importPrivateCheckBox.setEnabled(enable);
	importPassword.setEnabled(enable);
	showPasswordCheckBox.setEnabled(enable);
	if (!enable)
	    showPasswordCheckBox.setChecked(false);
	importButton.setEnabled(enable);
	cancelButton.setEnabled(enable);
	importProgressBar.setVisibility(enable ? View.GONE : View.VISIBLE);
	importProgressMessage.setVisibility(enable ? View.GONE : View.VISIBLE);
    }

    private static final DialogInterface.OnClickListener dismissListener =
	new DialogInterface.OnClickListener() {
	    @Override
	    public void onClick(DialogInterface dialog, int item) {
		dialog.dismiss();
	    }
	};

    /** Called when the user clicks Import to start importing the data */
    class ImportButtonOnClickListener implements View.OnClickListener {
	@Override
	public void onClick(View v) {
	    Log.d(TAG, "ImportButtonOK.onClick");
	    importProgressMessage.setText("...");
	    xableFormElements(false);
	    File importFile = new File(importFileName.getText().toString());
	    // Check whether the file is in external storage,
	    if (importFile.getParent().startsWith(
		    Environment.getExternalStorageDirectory().getPath())) {
		// and if so whether the external storage is available.
		String storageState = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED.equals(storageState) &&
			!Environment.MEDIA_MOUNTED_READ_ONLY.equals(storageState)) {
		    xableFormElements(true);
		    new AlertDialog.Builder(ImportActivity.this)
		    .setIcon(android.R.drawable.ic_dialog_alert)
		    .setTitle(getResources().getString(R.string.ErrorSDNotFound))
		    .setMessage(String.format(getResources().getString(
			    R.string.PromptMountStorage), importFile.getParent()))
		    .setNeutralButton(getResources().getString(
			    R.string.ConfirmationButtonOK), dismissListener)
		    .create().show();
		    return;
		}
	    }
	    // Check whether the file itself is available.
	    if (!importFile.exists()) {
		xableFormElements(true);
		new AlertDialog.Builder(ImportActivity.this)
		.setIcon(android.R.drawable.ic_dialog_alert)
		.setTitle(getResources().getString(R.string.ErrorFileNotFound))
		.setMessage(String.format(getResources().getString(
			R.string.ErrorCannotFind), importFile.getPath()))
		.setNeutralButton(getResources().getString(
			R.string.ConfirmationButtonCancel), dismissListener)
		.create().show();
		return;
	    }

	    Intent intent;
	    ServiceConnection serviceConnection;
	    int importType = importTypeList.getSelectedItemPosition();
	    if (importType == AdapterView.INVALID_POSITION)
		importType = 5;	// test
	    // Make an educated guess about the file type, based on the extension.
	    if (importFile.getName().toLowerCase().endsWith(".dat")) {
		// Assume Palm data
		intent = new Intent(ImportActivity.this, PalmImporterService.class);
		intent.putExtra(PalmImporterService.PALM_DATA_FILENAME,
			importFile.getAbsolutePath());
		intent.putExtra(PalmImporterService.PALM_IMPORT_TYPE,
			palmImportTypes[importType]);
		serviceConnection = new PalmImportServiceConnection();
	    } else {
		// Assume XML data exported by this application
		intent = new Intent(ImportActivity.this, XMLImporterService.class);
		intent.putExtra(XMLExporterService.XML_DATA_FILENAME,
			importFile.getAbsolutePath());
		intent.putExtra(XMLImporterService.XML_IMPORT_TYPE,
			xmlImportTypes[importType]);
		intent.putExtra(XMLImporterService.IMPORT_PRIVATE,
			importPrivateCheckBox.isChecked());
		if (importPrivateCheckBox.isChecked()) {
		    char[] password = new char[importPassword.length()];
		    importPassword.getText().getChars(0, importPassword.length(), password, 0);
		    if (password.length > 0)
			intent.putExtra(XMLImporterService.OLD_PASSWORD,
				password);
		}
		serviceConnection = new XMLImportServiceConnection();
	    }

	    // Set up a callback to update the progress bar
	    final Handler progressHandler = new Handler();
	    progressHandler.postDelayed(new Runnable() {
		int oldMax = 0;
		String oldMessage = "...";
		@Override
		public void run() {
		    if (progressService != null) {
			String newMessage = progressService.getCurrentMode();
			int newMax = progressService.getMaxCount();
			int newProgress = progressService.getChangedCount();
			Log.d(TAG, ".Runnable: Updating the progress dialog to "
				+ newMessage + " " + newProgress + "/" + newMax);
			if (!oldMessage.equals(newMessage)) {
			    importProgressMessage.setText(newMessage);
			    oldMessage = newMessage;
			}
			if (newMax != oldMax) {
			    importProgressBar.setIndeterminate(newMax == 0);
			    importProgressBar.setMax(newMax);
			    oldMax = newMax;
			}
			importProgressBar.setProgress(newProgress);
			// To do: also display the values (if max > 0)
			progressHandler.postDelayed(this, 100);
		    }
		}
	    }, 100);
	    startService(intent);
	    Log.d(TAG, "ImportButtonOK.onClick: binding to the import service");
	    bindService(intent, serviceConnection, 0);
	}
    }

    class PalmImportServiceConnection implements ServiceConnection {
	public void onServiceConnected(ComponentName name, IBinder service) {
	    try {
		Log.d(TAG, ".PalmImportServiceConnection.onServiceConnected("
			+ name.getShortClassName() + ","
			+ service.getInterfaceDescriptor() + ")");
	    } catch (RemoteException rx) {}
	    PalmImporterService.ImportBinder xbinder =
		(PalmImporterService.ImportBinder) service;
	    progressService = xbinder.getService();
	}

	/** Called when a connection to the service has been lost */
	public void onServiceDisconnected(ComponentName name) {
	    Log.d(TAG, ".onServiceDisconnected(" + name.getShortClassName() + ")");
	    xableFormElements(true);
	    progressService = null;
	    unbindService(this);
	}
    }

    class XMLImportServiceConnection implements ServiceConnection {
	public void onServiceConnected(ComponentName name, IBinder service) {
	    try {
		Log.d(TAG, ".XMLImportServiceConnection.onServiceConnected("
			+ name.getShortClassName() + ","
			+ service.getInterfaceDescriptor() + ")");
	    } catch (RemoteException rx) {}
	    XMLImporterService.ImportBinder xbinder =
		(XMLImporterService.ImportBinder) service;
	    progressService = xbinder.getService();
	}

	/** Called when a connection to the service has been lost */
	public void onServiceDisconnected(ComponentName name) {
	    Log.d(TAG, ".onServiceDisconnected(" + name.getShortClassName() + ")");
	    xableFormElements(true);
	    progressService = null;
	    unbindService(this);
	    // To do: was the import successful?
	    ImportActivity.this.finish();
	}
    }
}
