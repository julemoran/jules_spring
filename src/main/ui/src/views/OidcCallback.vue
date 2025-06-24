<template>
  <div>
    <p>Processing login...</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { useAuth } from '@/services/authService'; // will be created
import { useRouter } from 'vue-router';

const auth = useAuth();
const router = useRouter();

onMounted(async () => {
  try {
    await auth.handleRedirectCallback();
    // Attempt to retrieve the original path from session storage or a default
    const originalPath = sessionStorage.getItem('redirectAfterLogin') || '/';
    sessionStorage.removeItem('redirectAfterLogin');
    router.push(originalPath);
  } catch (error) {
    console.error('Error handling OIDC callback:', error);
    // Redirect to an error page or home page
    router.push('/');
  }
});
</script>
