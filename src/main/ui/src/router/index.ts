import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import LocationsView from '../views/LocationsView.vue'
import OidcCallback from '../views/OidcCallback.vue' // For handling OIDC redirect
// We will create authService later, for now, we just prepare for it
import { useAuth } from '@/services/authService'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView
    },
    {
      path: '/locations',
      name: 'locations',
      component: LocationsView,
      meta: { requiresAuth: true } // Mark this route as requiring authentication
    },
    {
      path: '/auth/callback', // Path where OIDC provider redirects after login
      name: 'oidc-callback',
      component: OidcCallback
    }
    // Removed About route
  ]
})

router.beforeEach(async (to, from, next) => {
  const auth = useAuth(); // Get auth service instance

  if (to.meta.requiresAuth) {
    await auth.ensureInitialized(); // Ensure OIDC is loaded before checking auth status
    if (!auth.isAuthenticated.value) {
      // Store the intended path to redirect after login
      sessionStorage.setItem('redirectAfterLogin', to.fullPath);
      auth.login(); // This will trigger redirect to OIDC provider
      // next(false); // Prevent navigation to the secured route for now
    } else {
      next(); // User is authenticated, proceed
    }
  } else {
    next(); // Route does not require auth
  }
});

export default router
