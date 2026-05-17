<template>
  <v-app>
    <v-navigation-drawer permanent width="260">
      <v-list-item title="Music Library NG" subtitle="Local collection"></v-list-item>
      <v-divider></v-divider>
      <v-list nav>
        <v-list-item prepend-icon="mdi-music-box-multiple" title="Library" to="/"></v-list-item>
        <v-list-item prepend-icon="mdi-folder-cog" title="Collections" to="/collections"></v-list-item>
      </v-list>
    </v-navigation-drawer>

    <v-app-bar flat border>
      <v-app-bar-title>Music Library NG</v-app-bar-title>
    </v-app-bar>

    <v-main>
      <router-view />
    </v-main>
  </v-app>
</template>
