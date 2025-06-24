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
const isInitializing = ref(false); // To track if initializeUserManager is in progress

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

    // Add event listeners
    userManager.events.addUserLoaded(loadedUser => {
      console.log("User loaded:", loadedUser);
      user.value = loadedUser;
      isLoading.value = false;
    });

    userManager.events.addUserUnloaded(() => {
      console.log("User unloaded");
      user.value = null;
      isLoading.value = false; // State determined: no user
    });

    userManager.events.addSilentRenewError(error => {
      console.error("Silent renew error:", error);
      user.value = null; // Assume user is logged out on silent renew error
      isLoading.value = false; // State determined (error, likely no user)
    });

    userManager.events.addAccessTokenExpired(() => {
      console.log("Access token expired");
      // Depending on UX, might try silent sign-in or prompt for login
      // For now, just log. If automaticSilentRenew is true, library handles it.
      // If it fails, addSilentRenewError will be triggered.
      // If user interaction is required, this is a place to trigger it.
      // We might need to set user.value to null if renewal fails and isn't handled.
    });

    isInitialized.value = true;

    // Initial check for user
    const loadedUser = await userManager.getUser();
    if (loadedUser && !loadedUser.expired) {
      // userManager.events.addUserLoaded should fire and handle setting user.value and isLoading.value
      // No explicit action needed here as the event handler will take over.
      // However, getUser() itself might not fire userLoaded if user is from storage.
      // Let's ensure user.value and isLoading are set if user is found here directly.
      user.value = loadedUser; // Set user directly
      isLoading.value = false; // User found, loading is complete
    } else {
      // No user from getUser(). Try silent sign-in.
      try {
        // signinSilent will trigger userLoaded on success, or fail.
        // If it returns null, userLoaded won't fire.
        const silentUser = await userManager.signinSilent();
        if (silentUser) {
          // This case should be handled by userLoaded event.
          // If signinSilent resolves with a user, userLoaded event should have already fired.
        } else {
          // signinSilent resolved to null (no user session could be established silently)
          isLoading.value = false;
        }
      } catch (silentError) {
        console.info('Silent sign-in failed:', silentError);
        // If silent sign-in fails, it means no user is currently logged in.
        user.value = null; // Ensure user state is null
        isLoading.value = false; // Stop loading.
      }
    }
  } catch (error) {
    console.error('Error initializing OIDC UserManager:', error);
    isInitialized.value = false; // Indicate initialization failure
    isLoading.value = false; // Also set isLoading to false on initialization error
    // Potentially show an error message to the user or retry
  }
}

// Call this when the app starts or before the first protected route access
async function ensureInitialized() {
  if (!isInitialized.value && !isInitializing.value) {
    isInitializing.value = true;
    isLoading.value = true; // Signal that auth state is being determined
    await initializeUserManager();
    isInitializing.value = false;
    // isLoading.value will be set to false within initializeUserManager
    // or by event handlers after it completes. If initialization failed and didn't set it,
    // it should be set here, but initializeUserManager itself sets it on failure.
  } else if (isInitializing.value) {
    // If initialization is already in progress, wait for isLoading to become false
    // This ensures subsequent calls don't proceed until the first one resolves loading state.
    await new Promise(resolve => {
      const unwatch = watch(isLoading, (newValue, oldValue) => {
        // Wait until isLoading becomes false.
        if (!newValue) {
          unwatch();
          resolve(undefined);
        }
      }, { immediate: false }); // `immediate: false` to only trigger on change
                               // and if isLoading is already false, it should resolve quickly or watcher might not fire as intended.
                               // Let's check current state too.
      if (!isLoading.value && isInitialized.value) { // If already not loading and initialized, resolve immediately.
          unwatch(); // clean up watcher if created.
          resolve(undefined);
      }
    });
  }
  // If isInitialized.value is true and isInitializing.value is false,
  // it means initialization is complete, and nothing needs to be done.
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
