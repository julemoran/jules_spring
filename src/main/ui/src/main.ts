import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())

// Initialize Auth Service before mounting the app or using the router
import { useAuth } from './services/authService'
const auth = useAuth();

auth.ensureInitialized().then(() => {
  app.use(router) // Use router after auth is initialized
  app.mount('#app')
}).catch(error => {
  console.error("Failed to initialize auth service, app might not work correctly:", error);
  // Optionally, mount a fallback UI or show an error message
  // For now, we'll still try to mount the app but it might be in a broken state
  // if OIDC config is essential for all parts.
  app.use(router)
  app.mount('#app')
});
