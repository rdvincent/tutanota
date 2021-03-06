package de.tutao.tutanota.alarms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.UnrecoverableEntryException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

import de.tutao.tutanota.AndroidKeyStoreFacade;
import de.tutao.tutanota.Crypto;
import de.tutao.tutanota.CryptoError;
import de.tutao.tutanota.OperationType;
import de.tutao.tutanota.Utils;
import de.tutao.tutanota.push.SseStorage;

public class AlarmNotificationsManager {
	private static final String TAG = "AlarmNotificationsMngr";
	private static final String RECURRING_ALARMS_PREF_NAME = "RECURRING_ALARMS";
	private final Context context;
	private final AndroidKeyStoreFacade keyStoreFacade;
	private final SseStorage sseStorage;
	private final Crypto crypto;

	public AlarmNotificationsManager(Context context, SseStorage sseStorage) {
		this.context = context;
		this.sseStorage = sseStorage;
		this.keyStoreFacade = new AndroidKeyStoreFacade(context);
		crypto = new Crypto(context);
	}

	public void reScheduleAlarms() {
		try {
			PushKeyResolver pushKeyResolver =
					new PushKeyResolver(keyStoreFacade, sseStorage.getPushIdentifierKeys());
			List<AlarmNotification> alarmInfos = this.readSavedAlarmNotifications();
			for (AlarmNotification alarmNotification : alarmInfos) {
				byte[] sessionKey = this.resolveSessionKey(alarmNotification, pushKeyResolver);
				if (sessionKey != null) {
					this.schedule(alarmNotification, sessionKey);
				}
			}
		} catch (IOException e) {
			Log.w(TAG, "Could not read pushIdentifierKeys", e);
		}

	}

	public byte[] resolveSessionKey(AlarmNotification notification, PushKeyResolver pushKeyResolver) {
		for (AlarmNotification.NotificationSessionKey notificationSessionKey : notification.getNotificationSessionKeys()) {
			try {
				byte[] pushIdentifierSessionKey = pushKeyResolver
						.resolvePushSessionKey(notificationSessionKey.getPushIdentifier().getElementId());
				if (pushIdentifierSessionKey != null) {

					byte[] encNotificationSessionKeyKey = Utils.base64ToBytes(notificationSessionKey.getPushIdentifierSessionEncSessionKey());
					return this.crypto.decryptKey(pushIdentifierSessionKey, encNotificationSessionKeyKey);
				}
			} catch (UnrecoverableEntryException | KeyStoreException | CryptoError e) {
				Log.w(TAG, "could not decrypt session key", e);
				return null;
			}
		}
		return null;
	}

	public void scheduleNewAlarms(List<AlarmNotification> alarmNotifications) {
		PushKeyResolver pushKeyResolver;
		try {
			pushKeyResolver = new PushKeyResolver(keyStoreFacade, sseStorage.getPushIdentifierKeys());
		} catch (IOException e) {
			Log.w(TAG, "Failed to read pushIdentifierKeys", e);
			return;
		}
		List<AlarmNotification> savedInfos = this.readSavedAlarmNotifications();
		for (AlarmNotification alarmNotification : alarmNotifications) {
			if (alarmNotification.getOperation() == OperationType.CREATE) {
				byte[] sessionKey = this.resolveSessionKey(alarmNotification, pushKeyResolver);
				if (sessionKey == null) {
					Log.d(TAG, "Failed to resolve session key for " + alarmNotification);
					return;
				}
				this.schedule(alarmNotification, sessionKey);
				if (alarmNotification.getRepeatRule() != null && !savedInfos.contains(alarmNotification)) {
					savedInfos.add(alarmNotification);
				}
			} else {
				this.cancelScheduledAlarm(alarmNotification, pushKeyResolver);
				savedInfos.remove(alarmNotification);
			}
		}
		this.writeAlarmInfos(savedInfos);
	}

	private List<AlarmNotification> readSavedAlarmNotifications() {
		String jsonString = PreferenceManager.getDefaultSharedPreferences(context)
				.getString(RECURRING_ALARMS_PREF_NAME, "[]");
		ArrayList<AlarmNotification> alarmInfos = new ArrayList<>();
		try {
			JSONArray jsonArray = new JSONArray(jsonString);
			for (int i = 0; i < jsonArray.length(); i++) {
				alarmInfos.add(AlarmNotification.fromJson(jsonArray.getJSONObject(i)));
			}
		} catch (JSONException e) {
			alarmInfos = new ArrayList<>();
		}
		return alarmInfos;
	}

	private void writeAlarmInfos(List<AlarmNotification> alarmNotifications) {
		List<JSONObject> jsonObjectList = new ArrayList<>(alarmNotifications.size());
		for (AlarmNotification alarmNotification : alarmNotifications) {
			jsonObjectList.add(alarmNotification.toJSON());
		}
		String jsonString = JSONObject.wrap(jsonObjectList).toString();
		PreferenceManager.getDefaultSharedPreferences(context)
				.edit()
				.putString(RECURRING_ALARMS_PREF_NAME, jsonString)
				.apply();
	}

	private void schedule(AlarmNotification alarmNotification, byte[] sessionKey) {
		try {
			String trigger = alarmNotification.getAlarmInfo().getTrigger(crypto, sessionKey);
			AlarmTrigger alarmTrigger = AlarmTrigger.get(trigger);
			String summary = alarmNotification.getSummary(crypto, sessionKey);
			String identifier = alarmNotification.getAlarmInfo().getIdentifier();
			Date eventStart = alarmNotification.getEventStart(crypto, sessionKey);

			if (alarmNotification.getRepeatRule() == null) {
				Date alarmTime = AlarmModel.calculateAlarmTime(eventStart, null, alarmTrigger);
				Date now = new Date();
				if (alarmTime.after(now)) {
					scheduleAlarmOccurrenceWithSystem(alarmTime, 0, identifier, summary, eventStart, alarmNotification.getUser());
				} else {
					Log.d(TAG, "Alarm " + identifier + " at " + alarmTime + " is before " + now + ", skipping");
				}
			} else {
				this.iterateAlarmOccurrences(alarmNotification, crypto, sessionKey, (alarmTime, occurrence, eventStartTime) ->
						this.scheduleAlarmOccurrenceWithSystem(alarmTime, occurrence, identifier, summary, eventStartTime, alarmNotification.getUser()));
			}
		} catch (CryptoError cryptoError) {
			Log.w(TAG, "Error when decrypting alarmNotificaiton", cryptoError);
		}
	}

	private void scheduleAlarmOccurrenceWithSystem(Date alarmTime, int occurrence,
												   String identifier, String summary,
												   Date eventDate, String user) {
		Log.d(TAG, "Scheduled notification " + identifier + " at: " + alarmTime);
		AlarmManager alarmManager = getAlarmManager();
		PendingIntent pendingIntent = makeAlarmPendingIntent(occurrence, identifier, summary, eventDate, user);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime.getTime(), pendingIntent);
		} else {
			alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmTime.getTime(), pendingIntent);
		}
	}

	private AlarmManager getAlarmManager() {
		return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
	}

	private void cancelScheduledAlarm(AlarmNotification alarmNotification,
									  PushKeyResolver pushKeyResolver) {
		// Read saved alarm because the sent one doesn't have a session key
		List<AlarmNotification> alarmNotifications = this.readSavedAlarmNotifications();
		int indexOfExistingRepeatingAlarm = alarmNotifications.indexOf(alarmNotification);

		// For cancellation we make alarms which are almost the same. Intent#filterEquals checks that action, data, type, class, and categories are the same.
		// It doesn't check extras. "data" (read: uri) is the only significant part. It is made up of alarm identifier and occurrence. We provide other fields
		// as a filler but this doesn't make a difference.
		// The DELETE notification we receive from the server has only placeholder fields and no keys. We must use our saved alarm to cancel notifications.
		if (indexOfExistingRepeatingAlarm == -1) {
			Log.d(TAG, "Cancelling alarm " + alarmNotification.getAlarmInfo().getIdentifier());
			PendingIntent pendingIntent = makeAlarmPendingIntent(0,
					alarmNotification.getAlarmInfo().getIdentifier(), "", new Date(), "");
			getAlarmManager().cancel(pendingIntent);
		} else {
			AlarmNotification savedAlarmNotification = alarmNotifications.get(indexOfExistingRepeatingAlarm);
			byte[] sessionKey = resolveSessionKey(savedAlarmNotification, pushKeyResolver);
			if (sessionKey != null) {
				try {
					this.iterateAlarmOccurrences(savedAlarmNotification, crypto, sessionKey, (alarmTime, occurrence, eventStartTime) -> {
						Log.d(TAG, "Cancelling alarm " + alarmNotification.getAlarmInfo().getIdentifier() + " # " + occurrence);
						getAlarmManager().cancel(makeAlarmPendingIntent(occurrence,
								alarmNotification.getAlarmInfo().getIdentifier(), "", new Date(), ""));
					});
				} catch (CryptoError cryptoError) {
					Log.w(TAG, "Failed to decrypt notification to cancel alarm " + savedAlarmNotification, cryptoError);
				}
			} else {
				Log.w(TAG, "Failed to resolve session key to cancel alarm " + savedAlarmNotification);
			}
		}
	}

	private PendingIntent makeAlarmPendingIntent(int occurrence, String identifier, String summary,
												 Date eventDate, String user) {
		Intent intent =
				AlarmBroadcastReceiver.makeAlarmIntent(context, occurrence, identifier, summary, eventDate, user);
		return PendingIntent.getBroadcast(context, 1, intent, 0);
	}

	private void iterateAlarmOccurrences(AlarmNotification alarmNotification, Crypto crypto,
										 byte[] sessionKey,
										 AlarmModel.AlarmIterationCallback callback
	) throws CryptoError {
		RepeatRule repeatRule = Objects.requireNonNull(alarmNotification.getRepeatRule());
		TimeZone timeZone = repeatRule.getTimeZone(crypto, sessionKey);

		Date eventStart = alarmNotification.getEventStart(crypto, sessionKey);
		Date eventEnd = alarmNotification.getEventEnd(crypto, sessionKey);
		RepeatPeriod frequency = repeatRule.getFrequency(crypto, sessionKey);
		int interval = repeatRule.getInterval(crypto, sessionKey);
		EndType endType = repeatRule.getEndType(crypto, sessionKey);
		long endValue = repeatRule.getEndValue(crypto, sessionKey);
		AlarmTrigger alarmTrigger = AlarmTrigger.get(
				alarmNotification.getAlarmInfo().getTrigger(crypto, sessionKey));

		AlarmModel.iterateAlarmOccurrences(new Date(),
				timeZone, eventStart, eventEnd, frequency, interval, endType,
				endValue, alarmTrigger, TimeZone.getDefault(), callback);
	}

	private static class PushKeyResolver {
		private AndroidKeyStoreFacade keyStoreFacade;
		private final Map<String, String> pushIdentifierToEncSessionKey;
		private final Map<String, byte[]> pushIdentifierToResolvedSessionKey = new HashMap<>();

		private PushKeyResolver(AndroidKeyStoreFacade keyStoreFacade, Map<String, String> pushIdentifierToEncSessionKey) {
			this.keyStoreFacade = keyStoreFacade;
			this.pushIdentifierToEncSessionKey = pushIdentifierToEncSessionKey;
		}

		@Nullable
		byte[] resolvePushSessionKey(String pushIdentifierId) throws UnrecoverableEntryException,
				KeyStoreException, CryptoError {
			byte[] resolved = pushIdentifierToResolvedSessionKey.get(pushIdentifierId);
			if (resolved != null) {
				return resolved;
			} else {
				String encryptedSessionKey = pushIdentifierToEncSessionKey.get(pushIdentifierId);
				if (encryptedSessionKey == null) {
					return null;
				}
				byte[] decryptedSessionKey = keyStoreFacade.decryptKey(encryptedSessionKey);
				pushIdentifierToResolvedSessionKey.put(pushIdentifierId, decryptedSessionKey);
				return decryptedSessionKey;
			}
		}
	}
}
