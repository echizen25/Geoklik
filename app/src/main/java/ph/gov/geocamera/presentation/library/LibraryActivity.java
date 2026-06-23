package ph.gov.geocamera.presentation.library;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.remote.ApiProjectItem;
import ph.gov.geocamera.data.remote.ProjectApiService;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.data.seed.ProjectSeedImporter;
import ph.gov.geocamera.presentation.common.BaseTopAppBarActivity;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;
import ph.gov.geocamera.presentation.home.HomeActivity;

public class LibraryActivity extends BaseTopAppBarActivity {

    private static final String TAG = "LIBRARY_SYNC";

    private RecyclerView rvProjects;
    private SwipeRefreshLayout swipeRefresh;
    private MaterialCardView cardEmpty;
    private MaterialCardView cardSearch;
    private AppCompatEditText etSearch;

    private LibraryProjectAdapter adapter;
    private final List<ProjectListItem> items = new ArrayList<>();

    private ProjectRepository projectRepository;
    private ProjectApiService apiService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean searchVisible = false;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_library;
    }

    @Override
    protected String getScreenTitle() {
        return "Projects Library";
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        projectRepository = new ProjectRepository(this);
        apiService = new ProjectApiService();

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
            toolbar.setOnMenuItemClickListener(this::onToolbarMenuClick);
        }

        rvProjects = findViewById(R.id.rvProjects);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        cardEmpty = findViewById(R.id.cardEmpty);
        cardSearch = findViewById(R.id.cardSearch);
        etSearch = findViewById(R.id.etSearch);

        adapter = new LibraryProjectAdapter(items);

        if (rvProjects != null) {
            rvProjects.setLayoutManager(new LinearLayoutManager(this));
            rvProjects.setAdapter(adapter);
        }

        setupSearch();
        setupPullRefresh();

        ProjectSeedImporter.importOrUpdate(getApplicationContext());

        loadProjectsFromLocal();
        syncProjectsFromApiIfOnline(false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        ProjectSeedImporter.importOrUpdate(getApplicationContext());
        loadProjectsFromLocal();
    }

    private boolean onToolbarMenuClick(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_search) {
            toggleSearch();
            return true;
        }

        if (id == R.id.action_sync) {
            Toast.makeText(this, "Syncing projects...", Toast.LENGTH_SHORT).show();
            syncProjectsFromApiIfOnline(true);
            return true;
        }

        if (id == R.id.action_geocam) {
            startActivity(new Intent(this, GeoCameraActivity.class));
            return true;
        }

        if (id == R.id.action_home) {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            return true;
        }

        return false;
    }

    private void setupPullRefresh() {
        if (swipeRefresh == null) return;

        swipeRefresh.setOnRefreshListener(() -> {
            ProjectSeedImporter.importOrUpdate(getApplicationContext());
            loadProjectsFromLocal();

            if (!isInternetAvailable()) {
                Toast.makeText(this, "Showing offline project list", Toast.LENGTH_SHORT).show();
                swipeRefresh.setRefreshing(false);
                return;
            }

            syncProjectsFromApiIfOnline(true);
        });
    }

    private void setupSearch() {
        if (etSearch == null) return;

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (adapter != null) {
                    adapter.getFilter().filter(s);
                    updateFilteredState();
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) { }
        });
    }

    private void toggleSearch() {
        searchVisible = !searchVisible;

        if (cardSearch != null) {
            cardSearch.setVisibility(searchVisible ? View.VISIBLE : View.GONE);
        }

        if (!searchVisible && etSearch != null) {
            etSearch.setText("");

            if (adapter != null) {
                adapter.getFilter().filter("");
            }

            updateFilteredState();
        }

        if (searchVisible && etSearch != null) {
            etSearch.requestFocus();
        }
    }

    private void loadProjectsFromLocal() {
        try {
            List<ProjectListItem> data = projectRepository.getProjectList();

            items.clear();

            if (data != null) {
                items.addAll(data);
            }

            Log.d(TAG, "Loaded from local DB: " + items.size());

            if (adapter != null) {
                adapter.refreshFromSource();
            }

            updateFilteredState();

        } catch (Exception e) {
            Log.e(TAG, "loadProjectsFromLocal() error", e);
        }
    }

    private void updateFilteredState() {
        int count = adapter == null ? 0 : adapter.getFilteredCount();
        boolean empty = count == 0;

        Log.d(TAG, "Filtered count = " + count);

        if (cardEmpty != null) {
            cardEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }

        if (rvProjects != null) {
            rvProjects.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void syncProjectsFromApiIfOnline(boolean showToast) {
        if (!isInternetAvailable()) {
            if (showToast) {
                Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show();
            }

            if (swipeRefresh != null) {
                swipeRefresh.setRefreshing(false);
            }
            return;
        }

        executor.execute(() -> {
            try {
                Log.d(TAG, "Starting API sync...");

                List<ApiProjectItem> apiItems = apiService.fetchProjects();
                Log.d(TAG, "API returned count = " + (apiItems == null ? 0 : apiItems.size()));

                if (apiItems != null && !apiItems.isEmpty()) {
                    for (ApiProjectItem item : apiItems) {
                        if (item != null) {
                            Log.d(TAG, "API ITEM => projectId=" + item.projectId
                                    + ", code=" + item.code
                                    + ", name=" + item.name
                                    + ", beneficiary=" + item.beneficiary
                                    + ", location=" + item.location
                                    + ", cost=" + item.cost);
                        }
                    }

                    projectRepository.saveProjectsFromApi(apiItems);
                }

                List<ProjectListItem> localItems = projectRepository.getProjectList();
                Log.d(TAG, "Local DB count after API save = " + (localItems == null ? 0 : localItems.size()));

                runOnUiThread(() -> {
                    loadProjectsFromLocal();

                    if (swipeRefresh != null) {
                        swipeRefresh.setRefreshing(false);
                    }

                    if (showToast) {
                        Toast.makeText(this, "Projects synced", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Sync failed", e);

                runOnUiThread(() -> {
                    if (swipeRefresh != null) {
                        swipeRefresh.setRefreshing(false);
                    }

                    if (showToast) {
                        Toast.makeText(this, "Sync failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
