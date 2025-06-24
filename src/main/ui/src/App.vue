<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { computed } from 'vue';
import { useAuth } from './services/authService'; // Will be created later

const auth = useAuth(); // Assuming useAuth provides login, logout, isAuthenticated, user
const isAuthenticated = computed(() => auth.isAuthenticated.value);
const user = computed(() => auth.user.value);

function login() {
  auth.login();
}

function logout() {
  auth.logout();
}
</script>

<template>
  <div id="app-layout">
    <header>
      <div class="wrapper">
        <nav>
          <RouterLink to="/">Home</RouterLink>
          <RouterLink to="/locations">Locations</RouterLink>
        </nav>
        <div class="auth-actions">
          <button v-if="!isAuthenticated" @click="login">Login</button>
          <div v-if="isAuthenticated">
            <span>Welcome, {{ user?.profile?.name || user?.profile?.preferred_username || 'User' }}</span>
            <button @click="logout">Logout</button>
          </div>
        </div>
      </div>
    </header>

    <main>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
#app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

header {
  background-color: #f8f9fa;
  padding: 1rem;
  border-bottom: 1px solid #e7e7e7;
}

.wrapper {
  display: flex;
  justify-content: space-between;
  align-items: center;
  max-width: 1200px;
  margin: 0 auto;
}

nav {
  display: flex;
  gap: 1rem; /* spacing between nav links */
}

nav a {
  text-decoration: none;
  color: #333;
  font-weight: bold;
  padding: 0.5rem;
}

nav a.router-link-exact-active {
  color: #007bff;
  border-bottom: 2px solid #007bff;
}

.auth-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.auth-actions button {
  padding: 0.5rem 1rem;
  cursor: pointer;
  border: 1px solid #007bff;
  background-color: #007bff;
  color: white;
  border-radius: 4px;
}

.auth-actions button:hover {
  background-color: #0056b3;
}

.auth-actions span {
  margin-right: 0.5rem;
}

main {
  flex-grow: 1;
  padding: 1rem;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
}
</style>
