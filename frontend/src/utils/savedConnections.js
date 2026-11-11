const STORAGE_KEY = 'pushdb_saved_connections';

// La contraseña de la base de datos NUNCA se persiste en localStorage (ver auditoría,
// hallazgo crítico: antes de este fix, el perfil completo -password incluida- de cada
// conexión guardada quedaba en texto plano en el navegador, exponiendo credenciales de
// infraestructura ante XSS, extensiones maliciosas o acceso físico al equipo). El usuario
// debe volver a escribir la contraseña cada vez que carga un perfil guardado.
function stripSensitiveFields(profile) {
  const { password: _password, ...safeProfile } = profile;
  return safeProfile;
}

export function loadSavedConnections() {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    return saved ? JSON.parse(saved) : [];
  } catch {
    return [];
  }
}

function persist(connections) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(connections));
  return connections;
}

export function saveConnectionProfile(existingConnections, profile) {
  const name = profile.profileName;
  const newProfile = stripSensitiveFields({
    ...profile,
    id: profile.id || Date.now().toString(),
    savedAt: profile.savedAt || new Date().toLocaleDateString()
  });

  const updated = [newProfile, ...existingConnections.filter((c) => c.profileName !== name)];
  return persist(updated);
}

export function deleteSavedConnection(existingConnections, id) {
  const updated = existingConnections.filter((c) => c.id !== id);
  return persist(updated);
}
