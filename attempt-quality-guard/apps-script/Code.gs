/**
 * Attempt Quality Guard - Google Apps Script backend.
 *
 * Bind this script to the Google Sheet that will hold all attempt logs
 * (Extensions > Apps Script from within that Sheet), paste this file in,
 * set SCRIPT_SHARED_SECRET below, then deploy as a Web App
 * (Execute as: Me, Who has access: Anyone with the link).
 *
 * Every Android device that points AppConfig.APPS_SCRIPT_WEB_APP_URL at the
 * same deployed Web App URL appends rows to this same Sheet.
 */

// Change this to a value only you and your teammates know, and paste the
// same value into AppConfig.SHARED_SECRET in the Android app.
var SCRIPT_SHARED_SECRET = 'CHANGE_ME_FOR_MVP';

var RAW_EVENTS_SHEET_NAME = 'Raw_Events';
var ATTEMPT_SUMMARY_SHEET_NAME = 'Attempt_Summary';

var RAW_EVENTS_COLUMNS = [
  'server_ts',
  'device_ts',
  'app_install_id',
  'device_model',
  'android_version',
  'app_version',
  'call_attempt_id',
  'event_name',
  'phone_state',
  'phone_number_masked',
  'phone_number_hash',
  'lat',
  'lng',
  'location_accuracy_m',
  'customer_lat',
  'customer_lng',
  'distance_to_customer_m',
  'call_state_duration_sec',
  'permission_call_phone',
  'permission_read_phone_state',
  'permission_location',
  'metadata_json',
];

var ATTEMPT_SUMMARY_COLUMNS = [
  'server_ts',
  'validation_ts_device',
  'app_install_id',
  'device_model',
  'android_version',
  'app_version',
  'call_attempt_id',
  'phone_number_masked',
  'phone_number_hash',
  'call_initiated_from_app',
  'phone_entered_call_state',
  'call_state_started_at',
  'call_state_ended_at',
  'call_state_duration_sec',
  'call_state_lasted_15_sec',
  'call_happened_within_last_10_min',
  'fe_lat',
  'fe_lng',
  'location_accuracy_m',
  'customer_lat',
  'customer_lng',
  'distance_to_customer_m',
  'fe_near_customer_location',
  'final_decision',
  'missing_signals',
  'metadata_json',
];

function doGet(e) {
  return jsonResponse({ success: true, message: 'Attempt Quality Guard logging endpoint is alive.' });
}

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({ success: false, error: 'Missing request body.' });
    }

    var payload = JSON.parse(e.postData.contents);

    if (!payload || payload.secret !== SCRIPT_SHARED_SECRET) {
      return jsonResponse({ success: false, error: 'Invalid or missing secret.' });
    }

    var type = payload.type;
    var data = payload.data || {};

    if (type === 'raw_event') {
      appendRow(RAW_EVENTS_SHEET_NAME, RAW_EVENTS_COLUMNS, data);
      return jsonResponse({ success: true, sheet: RAW_EVENTS_SHEET_NAME });
    }

    if (type === 'attempt_summary') {
      appendRow(ATTEMPT_SUMMARY_SHEET_NAME, ATTEMPT_SUMMARY_COLUMNS, data);
      return jsonResponse({ success: true, sheet: ATTEMPT_SUMMARY_SHEET_NAME });
    }

    return jsonResponse({ success: false, error: 'Unknown type: ' + type });
  } catch (err) {
    return jsonResponse({ success: false, error: String(err) });
  }
}

function appendRow(sheetName, columns, data) {
  var sheet = getOrCreateSheetWithHeaders(sheetName, columns);

  var row = columns.map(function (columnName) {
    if (columnName === 'server_ts') {
      return new Date();
    }
    var value = data[columnName];
    if (value === undefined || value === null) {
      return '';
    }
    return value;
  });

  sheet.appendRow(row);
}

function getOrCreateSheetWithHeaders(sheetName, columns) {
  var spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = spreadsheet.getSheetByName(sheetName);

  if (!sheet) {
    sheet = spreadsheet.insertSheet(sheetName);
  }

  var firstRow = sheet.getRange(1, 1, 1, columns.length).getValues()[0];
  var headersPresent = columns.every(function (columnName, index) {
    return firstRow[index] === columnName;
  });

  if (!headersPresent) {
    sheet.getRange(1, 1, 1, columns.length).setValues([columns]);
    sheet.setFrozenRows(1);
  }

  return sheet;
}

function jsonResponse(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
