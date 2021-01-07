package threads.server.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.URLUtil;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.psdev.licensesdialog.LicensesDialog;
import threads.LogUtils;
import threads.server.InitApplication;
import threads.server.R;
import threads.server.core.events.EVENTS;
import threads.server.core.events.EventViewModel;
import threads.server.core.peers.Content;
import threads.server.ipfs.CID;
import threads.server.ipfs.IPFS;
import threads.server.provider.FileDocumentsProvider;
import threads.server.services.DaemonService;
import threads.server.services.LiteService;
import threads.server.utils.MimeType;
import threads.server.utils.StorageLocation;
import threads.server.work.PageWorker;

public class SettingsFragment extends Fragment {

    private static final String TAG = SettingsFragment.class.getSimpleName();

    private Context mContext;
    private TextView mSwarmKey;
    private final ActivityResultLauncher<Intent> mFileForResult = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();

                        try {
                            Objects.requireNonNull(data);

                            Uri uri = data.getData();
                            Objects.requireNonNull(uri);
                            if (!FileDocumentsProvider.hasReadPermission(mContext, uri)) {
                                EVENTS.getInstance(mContext).error(
                                        getString(R.string.file_has_no_read_permission));
                                return;
                            }

                            if (FileDocumentsProvider.getFileSize(mContext, uri) > 500) {
                                EVENTS.getInstance(mContext).error(
                                        getString(R.string.swarm_key_not_valid));
                            }

                            try (InputStream is = mContext.getContentResolver().openInputStream(uri)) {
                                Objects.requireNonNull(is);
                                String key;
                                try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                                    IPFS.copy(is, os);
                                    key = os.toString();
                                }

                                IPFS ipfs = IPFS.getInstance(mContext);
                                ipfs.checkSwarmKey(key);

                                IPFS.setSwarmKey(mContext, key);
                                mSwarmKey.setText(key);

                                if (IPFS.isPrivateNetworkEnabled(mContext)) {
                                    EVENTS.getInstance(mContext).exit(
                                            getString(R.string.daemon_restart_config_changed));
                                }
                            } catch (Throwable throwable) {
                                LogUtils.error(TAG, throwable);
                                EVENTS.getInstance(mContext).error(
                                        getString(R.string.swarm_key_not_valid));
                            }


                        } catch (Throwable e) {
                            LogUtils.error(TAG, e);
                        }
                    }
                }
            });

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mContext = null;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        super.onCreateOptionsMenu(menu, menuInflater);
        menuInflater.inflate(R.menu.menu_settings_fragment, menu);
    }

    public boolean onOptionsItemSelected(@NonNull MenuItem item) {

        int itemId = item.getItemId();
        if (itemId == R.id.action_privacy_policy) {

            try {

                String data;
                if (LiteService.isDarkMode(mContext)) {
                    data = LiteService.loadRawData(mContext, R.raw.privacy_policy_night);
                } else {
                    data = LiteService.loadRawData(mContext, R.raw.privacy_policy);
                }

                IPFS ipfs = IPFS.getInstance(mContext);
                CID cid = ipfs.storeText(data);
                Objects.requireNonNull(cid);
                String uri = Content.IPFS + "://" + cid.getCid();

                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            }
            return true;
        } else if (itemId == R.id.action_issues) {
            try {
                String uri = "https://gitlab.com/remmer.wilts/ipfs-lite/issues";

                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            }
            return true;
        } else if (itemId == R.id.action_documentation) {
            try {
                String uri = "https://gitlab.com/remmer.wilts/ipfs-lite";

                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            }
            return true;
        } else if (itemId == R.id.action_licences) {
            try {
                new LicensesDialog.Builder(mContext)
                        .setTitle(R.string.licences)
                        .setNotices(R.raw.licenses)
                        .setShowFullLicenseText(false)
                        .setIncludeOwnLicense(true)
                        .build().show();

            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            }
            return true;
        }

        return super.onOptionsItemSelected(item);

    }


    private String getData(@NonNull Context context, long size) {

        String fileSize;

        if (size < 1000) {
            fileSize = String.valueOf(size);
            return context.getString(R.string.traffic, fileSize);
        } else if (size < 1000 * 1000) {
            fileSize = String.valueOf((double) (size / 1000));
            return context.getString(R.string.traffic_kb, fileSize);
        } else {
            fileSize = String.valueOf((double) (size / (1000 * 1000)));
            return context.getString(R.string.traffic_mb, fileSize);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_view, container, false);
    }

    private boolean isValid(@NonNull String gateway) {
        return URLUtil.isValidUrl(gateway);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


        IPFS ipfs = IPFS.getInstance(mContext);


        TextView seeding = view.findViewById(R.id.seeding);
        TextView leeching = view.findViewById(R.id.leeching);
        TextView reachable = view.findViewById(R.id.reachable);
        TextView port = view.findViewById(R.id.port);

        port.setText(String.valueOf(ipfs.getSwarmPort()));

        EventViewModel eventViewModel =
                new ViewModelProvider(this).get(EventViewModel.class);

        eventViewModel.getSeeding().observe(getViewLifecycleOwner(), (event) -> {
            try {
                seeding.setText(getData(mContext, ipfs.getSeeding()));
            } catch (Throwable e) {
                LogUtils.error(TAG, "" + e.getLocalizedMessage(), e);
            }

        });

        eventViewModel.getLeeching().observe(getViewLifecycleOwner(), (event) -> {
            try {
                leeching.setText(getData(mContext, ipfs.getLeeching()));
            } catch (Throwable e) {
                LogUtils.error(TAG, "" + e.getLocalizedMessage(), e);
            }

        });

        eventViewModel.getReachable().observe(getViewLifecycleOwner(), (event) -> {
            try {
                reachable.setText(ipfs.getReachable().name());
            } catch (Throwable e) {
                LogUtils.error(TAG, "" + e.getLocalizedMessage(), e);
            }

        });
        boolean issueWarning = ipfs.isPrivateNetwork() || IPFS.isPrivateSharingEnabled(mContext);


        TextView warning_text = view.findViewById(R.id.warning_text);
        if (!issueWarning) {
            warning_text.setVisibility(View.GONE);
        } else {
            warning_text.setVisibility(View.VISIBLE);
            if (IPFS.isPrivateSharingEnabled(mContext)) {
                warning_text.setText(getString(R.string.private_sharing));
            } else {
                warning_text.setText(getString(R.string.private_network));
            }
        }


        ImageView daemonStart = view.findViewById(R.id.daemon_start);
        daemonStart.setOnClickListener(view1 -> {
            EVENTS.getInstance(mContext).warning(getString(R.string.server_mode));
            DaemonService.start(mContext);
        });


        TextView automatic_discovery_mode_text = view.findViewById(R.id.automatic_discovery_mode_text);

        String auto_discovery_html = getString(R.string.automatic_discovery_mode_text);
        automatic_discovery_mode_text.setTextAppearance(android.R.style.TextAppearance_Small);
        automatic_discovery_mode_text.setText(Html.fromHtml(auto_discovery_html, Html.FROM_HTML_MODE_LEGACY));

        SwitchMaterial automatic_discovery_mode = view.findViewById(R.id.automatic_discovery_mode);
        automatic_discovery_mode.setChecked(InitApplication.isAutoDiscovery(mContext));
        automatic_discovery_mode.setOnCheckedChangeListener((buttonView, isChecked) ->
                InitApplication.setAutoDiscovery(mContext, isChecked)
        );


        TextView private_sharing_mode_text = view.findViewById(R.id.private_sharing_mode_text);

        String private_sharing_mode_html = getString(R.string.private_sharing_mode_text);
        private_sharing_mode_text.setTextAppearance(android.R.style.TextAppearance_Small);
        private_sharing_mode_text.setText(Html.fromHtml(private_sharing_mode_html, Html.FROM_HTML_MODE_LEGACY));


        SwitchMaterial private_sharing_mode = view.findViewById(R.id.private_sharing_mode);
        private_sharing_mode.setChecked(IPFS.isPrivateSharingEnabled(mContext));
        private_sharing_mode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    try {
                        IPFS.setPrivateSharingEnabled(mContext, isChecked);
                        EVENTS.getInstance(mContext).exit(
                                getString(R.string.daemon_restart_config_changed));
                    } catch (Throwable throwable) {
                        LogUtils.error(TAG, throwable);
                    }
                }
        );


        TextInputLayout mLayout = view.findViewById(R.id.gateway_layout);
        TextInputEditText mGateway = view.findViewById(R.id.gateway);
        mGateway.setText(LiteService.getGateway(mContext));

        mGateway.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > 0) mLayout.setError(null);
            }
        });
        mGateway.setOnClickListener(v -> mGateway.setCursorVisible(true));

        mGateway.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE ||
                            event != null &&
                                    event.getAction() == KeyEvent.ACTION_DOWN &&
                                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                        if (event == null || !event.isShiftPressed()) {


                            Editable text = mGateway.getText();
                            Objects.requireNonNull(text);
                            String gateway = text.toString();

                            mGateway.setCursorVisible(false);


                            if (!isValid(gateway)) {
                                mLayout.setError(getString(R.string.gateway_not_valid));
                            } else {
                                mLayout.setError(null);
                                ExecutorService executor = Executors.newSingleThreadExecutor();
                                executor.submit(() -> {
                                    try {
                                        URI uri = new URI(gateway);
                                        if (InetAddress.getByName(uri.getHost()).isReachable(2000)) {
                                            LiteService.setGateway(mContext, gateway);
                                        } else {

                                            new Handler(Looper.getMainLooper())
                                                    .post(() -> mLayout.setError(
                                                            getString(R.string.gateway_not_reachable)));

                                        }
                                    } catch (Exception e) {
                                        LogUtils.error(TAG, e);
                                        new Handler(Looper.getMainLooper())
                                                .post(() -> mLayout.setError(
                                                        getString(R.string.gateway_not_reachable)));
                                    }
                                });
                            }
                        }
                    }
                    return false; // pass on to other listeners.
                }
        );
        TextView publisher_service_time_text = view.findViewById(R.id.publisher_service_time_text);
        SeekBar publisher_service_time = view.findViewById(R.id.publisher_service_time);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            publisher_service_time.setMin(2);
        }
        publisher_service_time.setMax(12);
        int time = 0;
        int pinServiceTime = LiteService.getPublishServiceTime(mContext);
        if (pinServiceTime > 0) {
            time = (pinServiceTime);
        }
        publisher_service_time_text.setText(getString(R.string.publisher_service_time,
                String.valueOf(time)));
        publisher_service_time.setProgress(time);
        publisher_service_time.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                LiteService.setPublisherServiceTime(mContext, progress);
                PageWorker.publish(mContext, true);
                publisher_service_time_text.setText(
                        getString(R.string.publisher_service_time,
                                String.valueOf(progress)));
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
                // ignore, not used
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                // ignore, not used
            }
        });

        TextView connection_timeout_text = view.findViewById(R.id.connection_timeout_text);
        SeekBar connection_timeout = view.findViewById(R.id.connection_timeout);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connection_timeout.setMin(15);
        }
        connection_timeout.setMax(120);

        int connectionTimeout = InitApplication.getConnectionTimeout(mContext);

        connection_timeout_text.setText(getString(R.string.connection_timeout,
                String.valueOf(connectionTimeout)));
        connection_timeout.setProgress(connectionTimeout);
        connection_timeout.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                InitApplication.setConnectionTimeout(mContext, progress);
                connection_timeout_text.setText(
                        getString(R.string.connection_timeout,
                                String.valueOf(progress)));

            }

            public void onStartTrackingTouch(SeekBar seekBar) {
                // ignore, not used
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                // ignore, not used
            }
        });


        Spinner storage_location = view.findViewById(R.id.storage_location);

        List<StorageLocation> locations = LiteService.getStorageLocations(mContext);
        ArrayAdapter<StorageLocation> locationAdapter = new ArrayAdapter<>(mContext,
                android.R.layout.simple_spinner_item, locations);
        locationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        storage_location.setAdapter(locationAdapter);

        int locPos = locations.indexOf(LiteService.getStorageLocation(mContext));
        storage_location.setSelection(locPos);
        storage_location.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long l) {

                StorageLocation location = (StorageLocation)
                        parent.getItemAtPosition(pos);

                File prevValue = IPFS.getExternalStorageDirectory(mContext);
                boolean issueMessage = !Objects.equals(location.getFile(), prevValue);
                if (location.isPrimary()) {
                    IPFS.setExternalStorageDirectory(mContext, null);
                    issueMessage = prevValue != null;
                } else {
                    IPFS.setExternalStorageDirectory(mContext, location.getFile());
                }


                if (issueMessage) {
                    EVENTS.getInstance(mContext).exit(
                            getString(R.string.daemon_restart_config_changed));

                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });


        mSwarmKey = view.findViewById(R.id.swarm_key);
        mSwarmKey.setText(IPFS.getSwarmKey(mContext));


        ImageView swarm_key_action = view.findViewById(R.id.swarm_key_action);
        swarm_key_action.setOnClickListener(v -> {

            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType(MimeType.ALL);
                String[] mimeTypes = {MimeType.ALL};
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                intent.putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, false);
                intent.addCategory(Intent.CATEGORY_OPENABLE);

                mFileForResult.launch(intent);

            } catch (Throwable e) {
                EVENTS.getInstance(mContext).warning(
                        getString(R.string.no_activity_found_to_handle_uri));
            }
        });


        SwitchMaterial enable_private_network = view.findViewById(R.id.enable_private_network);
        enable_private_network.setChecked(IPFS.isPrivateNetworkEnabled(mContext));
        enable_private_network.setOnCheckedChangeListener((buttonView, isChecked) -> {
            IPFS.setPrivateNetworkEnabled(mContext, isChecked);
            EVENTS.getInstance(mContext).exit(
                    getString(R.string.daemon_restart_config_changed));

        });


    }

}