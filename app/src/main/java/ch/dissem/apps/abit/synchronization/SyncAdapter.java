package ch.dissem.apps.abit.synchronization;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Bundle;
import android.preference.PreferenceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

import ch.dissem.apps.abit.R;
import ch.dissem.apps.abit.notification.ErrorNotification;
import ch.dissem.apps.abit.service.Singleton;
import ch.dissem.bitmessage.BitmessageContext;

import static ch.dissem.apps.abit.util.Constants.PREFERENCE_SYNC_TIMEOUT;
import static ch.dissem.apps.abit.util.Constants.PREFERENCE_TRUSTED_NODE;

/**
 * Sync Adapter to synchronize with the Bitmessage network - fetches
 * new objects and then disconnects.
 */
public class SyncAdapter extends AbstractThreadedSyncAdapter {
    private final static Logger LOG = LoggerFactory.getLogger(SyncAdapter.class);

    private final BitmessageContext bmc;

    /**
     * Set up the sync adapter
     */
    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        bmc = Singleton.getBitmessageContext(context);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        // If the Bitmessage context acts as a full node, synchronization isn't necessary
        if (bmc.isRunning()) {
            LOG.info("Synchronization skipped, Abit is acting as a full node");
            return;
        }
        LOG.info("Synchronizing Bitmessage");

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        String trustedNode = preferences.getString(PREFERENCE_TRUSTED_NODE, null);
        if (trustedNode == null) return;
        trustedNode = trustedNode.trim();
        if (trustedNode.isEmpty()) return;

        int port;
        if (trustedNode.matches("^(?![0-9a-fA-F]*:[0-9a-fA-F]*:).*(:[0-9]+)$")) {
            int index = trustedNode.lastIndexOf(':');
            String portString = trustedNode.substring(index + 1);
            trustedNode = trustedNode.substring(0, index);
            try {
                port = Integer.parseInt(portString);
            } catch (NumberFormatException e) {
                new ErrorNotification(getContext())
                        .setError(R.string.error_invalid_sync_port, portString)
                        .show();
                return;
            }
        } else {
            port = 8444;
        }
        long timeoutInSeconds = Long.parseLong(preferences.getString(PREFERENCE_SYNC_TIMEOUT, "120"));
        try {
            LOG.info("Synchronization started");
            bmc.synchronize(InetAddress.getByName(trustedNode), port, timeoutInSeconds, true);
            LOG.info("Synchronization finished");
        } catch (UnknownHostException e) {
            new ErrorNotification(getContext())
                    .setError(R.string.error_invalid_sync_host)
                    .show();
        } catch (RuntimeException e) {
            LOG.error(e.getMessage(), e);
        }
    }
}
