<template>
  <div class="locations-view">
    <h1>Locations</h1>
    <p>Manage your warehouse locations.</p>

    <!-- Add Location Form -->
    <form @submit.prevent="addLocation" class="add-location-form">
      <h2>Add New Location</h2>
      <div>
        <label for="name">Name:</label>
        <input type="text" id="name" v-model="newLocation.name" required />
      </div>
      <div>
        <label for="physicalPath">Physical Path:</label>
        <input type="text" id="physicalPath" v-model="newLocation.physicalPath" required />
      </div>
      <button type="submit">Add Location</button>
      <p v-if="errorAddingLocation" class="error-message">{{ errorAddingLocation }}</p>
    </form>

    <!-- Locations Table -->
    <h2>Current Locations</h2>
    <div v-if="isLoading" class="loading-message">Loading locations...</div>
    <div v-if="errorLoadingLocations" class="error-message">
      Error loading locations: {{ errorLoadingLocations }}
    </div>
    <table v-if="!isLoading && !errorLoadingLocations && locations.length > 0" class="locations-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>Name</th>
          <th>Physical Path</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="location in locations" :key="location.id">
          <td>{{ location.id }}</td>
          <td>{{ location.name }}</td>
          <td>{{ location.physicalPath }}</td>
          <td>
            <button @click="deleteLocation(location.name)" class="delete-button">Delete</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-if="!isLoading && locations.length === 0 && !errorLoadingLocations">No locations configured yet.</p>
    <p v-if="errorDeletingLocation" class="error-message">{{ errorDeletingLocation }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useAuth } from '@/services/authService'; // Assuming authService provides token or handles auth

// Define the Location type based on expected API response
// Will update this if ID is not directly available or named differently initially
interface Location {
  id: number | string; // Assuming id is part of the DTO, adjust if not
  name: string;
  physicalPath: string;
}

const locations = ref<Location[]>([]);
const newLocation = ref({ name: '', physicalPath: '' });
const isLoading = ref(false);
const errorLoadingLocations = ref<string | null>(null);
const errorAddingLocation = ref<string | null>(null);
const errorDeletingLocation = ref<string | null>(null);

const auth = useAuth();

const API_BASE_URL = '/locations'; // Adjust if your API is hosted elsewhere or has a prefix

// Fetch locations
async function fetchLocations() {
  isLoading.value = true;
  errorLoadingLocations.value = null;
  try {
    await auth.ensureInitialized(); // Ensure auth is ready
    const accessToken = await auth.getAccessToken();
    const response = await fetch(API_BASE_URL, {
      headers: {
        'Authorization': `Bearer ${accessToken}`
      }
    });
    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: response.statusText }));
      throw new Error(errorData.message || `HTTP error! status: ${response.status}`);
    }
    const data = await response.json();
    // Assuming the backend DTO directly maps to the Location interface for now.
    // If the ID is not in the DTO, this will need adjustment later.
    locations.value = data;
  } catch (error: any) {
    console.error('Failed to fetch locations:', error);
    errorLoadingLocations.value = error.message || 'An unexpected error occurred.';
  } finally {
    isLoading.value = false;
  }
}

// Add a new location
async function addLocation() {
  errorAddingLocation.value = null;
  if (!newLocation.value.name || !newLocation.value.physicalPath) {
    errorAddingLocation.value = 'Name and Physical Path are required.';
    return;
  }
  try {
    await auth.ensureInitialized();
    const accessToken = await auth.getAccessToken();
    const response = await fetch(API_BASE_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${accessToken}`
      },
      body: JSON.stringify(newLocation.value)
    });
    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: response.statusText }));
      throw new Error(errorData.message || `HTTP error! status: ${response.status}`);
    }
    // const addedLocation = await response.json(); // Contains the newly added location with ID
    // To refresh the list and get the ID assigned by the backend:
    await fetchLocations(); // Re-fetch to see the new location with its ID
    newLocation.value = { name: '', physicalPath: '' }; // Reset form
  } catch (error: any) {
    console.error('Failed to add location:', error);
    errorAddingLocation.value = error.message || 'An unexpected error occurred while adding the location.';
  }
}

// Delete a location by its name (as per current backend controller)
// Note: Deleting by name might be problematic if names are not unique or can change.
// Prefer using ID if available and stable. This will be updated when backend DTO includes ID.
async function deleteLocation(name: string) {
  errorDeletingLocation.value = null;
  if (!confirm(`Are you sure you want to delete location "${name}"?`)) {
    return;
  }
  try {
    await auth.ensureInitialized();
    const accessToken = await auth.getAccessToken();
    const response = await fetch(`${API_BASE_URL}/${name}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${accessToken}`
      }
    });
    if (!response.ok) {
      // For 204 No Content, response.json() will fail. Check status first.
      if (response.status === 204) {
        // Success
      } else {
        const errorData = await response.json().catch(() => ({ message: response.statusText }));
        throw new Error(errorData.message || `HTTP error! status: ${response.status}`);
      }
    }
    await fetchLocations(); // Refresh the list
  } catch (error: any) {
    console.error('Failed to delete location:', error);
    errorDeletingLocation.value = error.message || 'An unexpected error occurred while deleting the location.';
  }
}

onMounted(() => {
  fetchLocations();
});
</script>

<style scoped>
.locations-view {
  max-width: 800px;
  margin: 2rem auto;
  padding: 1rem;
  font-family: Arial, sans-serif;
}

.add-location-form {
  background-color: #f9f9f9;
  padding: 1.5rem;
  border-radius: 8px;
  margin-bottom: 2rem;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.add-location-form h2 {
  margin-top: 0;
  margin-bottom: 1rem;
  font-size: 1.5em;
}

.add-location-form div {
  margin-bottom: 1rem;
}

.add-location-form label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: bold;
}

.add-location-form input[type="text"] {
  width: calc(100% - 22px); /* Adjust for padding and border */
  padding: 10px;
  border: 1px solid #ccc;
  border-radius: 4px;
  font-size: 1em;
}

.add-location-form button {
  background-color: #007bff;
  color: white;
  padding: 10px 15px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 1em;
}

.add-location-form button:hover {
  background-color: #0056b3;
}

.locations-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 1rem;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.locations-table th,
.locations-table td {
  border: 1px solid #ddd;
  padding: 12px;
  text-align: left;
}

.locations-table th {
  background-color: #007bff;
  color: white;
  font-size: 1.1em;
}

.locations-table tr:nth-child(even) {
  background-color: #f2f2f2;
}

.locations-table tr:hover {
  background-color: #e9ecef;
}

.delete-button {
  background-color: #dc3545;
  color: white;
  padding: 8px 12px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.9em;
}

.delete-button:hover {
  background-color: #c82333;
}

.loading-message, .error-message {
  margin-top: 1rem;
  padding: 10px;
  border-radius: 4px;
}

.loading-message {
  background-color: #eef;
  border: 1px solid #aac;
  color: #33a;
}

.error-message {
  background-color: #fdd;
  border: 1px solid #ebb;
  color: #a33;
  margin-bottom: 1rem;
}
</style>
