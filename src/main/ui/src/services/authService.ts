import { UserManager, WebStorageStateStore, User } from 'oidc-client-ts';
import { ref, computed, watch } from 'vue';

interface OidcFrontendConfig {
  clientId: string;
  issuerUri: string;
  scope: string;
  redirectUri: string;
}

// Reactive state for authentication
const user = ref<User | null>(null);
const isAuthenticated = computed(() => user.value !== null && !user.value.expired);
let userManager: UserManager | null = null;
let oidcConfig: OidcFrontendConfig | null = null;

const isInitialized = ref(false);
const isLoading = ref(true); // To track initial loading/redirect checks

// Function to fetch OIDC configuration from the backend
async function fetchOidcConfig(): Promise<OidcFrontendConfig> {
  const response = await fetch('/api/v1/config/oidc');
  if (!response.ok) {
    throw new Error('Failed to fetch OIDC configuration from backend');
  }
  return response.json();
}

// Initialize UserManager
async function initializeUserManager() {
  if (userManager) return;

  try {
    oidcConfig = await fetchOidcConfig();

    const settings = {
      authority: oidcConfig.issuerUri,
      client_id: oidcConfig.clientId,
      redirect_uri: `${window.location.origin}${oidcConfig.redirectUri}`, // Needs full URI
      post_logout_redirect_uri: window.location.origin, // Redirect to home after logout
      response_type: 'code', // Using Authorization Code Flow
      scope: oidcConfig.scope,
      userStore: new WebStorageStateStore({ store: window.localStorage }), // Store user data in local storage
      automaticSilentRenew: true, // Enable automatic token renewal
      // silent_redirect_uri: `${window.location.origin}/silent-refresh.html` // Optional: for silent token renewal
    };

    userManager = new UserManager(settings);
    isInitialized.value = true;

    // Check if user is already logged in (e.g., page refresh)
    const loadedUser = await userManager.getUser();
    if (loadedUser && !loadedUser.expired) {
      user.value = loadedUser;
    }
  } catch (error) {
    console.error('Error initializing OIDC UserManager:', error);
    isInitialized.value = false; // Indicate initialization failure
    // Potentially show an error message to the user or retry
  } finally {
    isLoading.value = false;
  }
}

// Call this when the app starts or before the first protected route access
async function ensureInitialized() {
  if (!isInitialized.value && !isLoading.value) { // Avoid re-initializing if already in progress or done
      isLoading.value = true; // Set loading true before async call
      await initializeUserManager();
  } else if (isLoading.value) { // If already loading, wait for it to complete
      await new Promise(resolve => {
          const unwatch = watch(isLoading, (newValue) => {
              if (!newValue) {
                  unwatch();
                  resolve(undefined);
              }
          });
      });
  }
}


async function login() {
  if (!userManager) {
    await ensureInitialized();
    if(!userManager) {
      console.error('UserManager not available for login.');
      return;
    }
  }
  try {
    await userManager.signinRedirect(); // Redirects to OIDC provider
  } catch (error) {
    console.error('Error during signinRedirect:', error);
  }
}

async function logout() {
  if (!userManager) {
    console.error('UserManager not available for logout.');
    return;
  }
  try {
    await userManager.signoutRedirect(); // Redirects to OIDC provider for logout
  } catch (error) {
    console.error('Error during signoutRedirect:', error);
  }
}

async function handleRedirectCallback() {
  if (!userManager) {
    // This case should ideally not happen if ensureInitialized is called correctly.
    // However, as a fallback, try to initialize.
    await ensureInitialized();
    if (!userManager) {
        console.error('UserManager not available to handle redirect callback.');
        throw new Error('UserManager not initialized for callback handling.');
    }
  }
  try {
    const returnedUser = await userManager.signinRedirectCallback();
    user.value = returnedUser;
    // The router will handle navigation after this promise resolves.
  } catch (error) {
    console.error('Error handling redirect callback:', error);
    user.value = null; // Ensure user is cleared on error
    throw error; // Re-throw to be caught by the calling component
  }
}

// Composable function to use in Vue components
export function useAuth() {
  return {
    user,
    isAuthenticated,
    isLoading, // Expose loading state for UI indicators
    isInitialized,
    login,
    logout,
    handleRedirectCallback,
    ensureInitialized // Expose this to be called in router.beforeEach or app setup
  };
}

// Initialize when the module is loaded, or call ensureInitialized in main.ts or App.vue
// For simplicity here, we might rely on ensureInitialized being called by router guard
// or app setup.
// Alternative: Auto-initialize, but be careful with async nature if other parts of app
// depend on it being immediately available.
// ensureInitialized(); // Consider if auto-init is desired or if router/app setup is better.
// For now, relying on router.beforeEach to call ensureInitialized.
// Also, App.vue might call it to get initial user state for display.
// This ensures config is loaded before any auth action is attempted or state is checked.
