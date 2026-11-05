import {
  defineRailway,
  github,
  preserve,
  project,
  service,
  volume,
} from "railway/iac";

export default defineRailway(() => {
  const data = volume("asistencia-data", {
    region: "us-east4-eqdc4a",
    sizeMB: 500,
  });

  const api = service("asistencia-api", {
    source: github("CrisAcosta1705/Proyecto-de-IA", { branch: "main" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "/Dockerfile",
    },
    healthcheck: "/health",
    healthcheckTimeout: 900,
    replicas: { "us-east4-eqdc4a": 1 },
    env: {
      ASISTENCIA_API_KEY: preserve(),
      ASISTENCIA_DB: "/var/data/asistencia.db",
      FACE_SIMILARITY_THRESHOLD: "0.45",
      INSIGHTFACE_MODEL: "buffalo_sc",
      INSIGHTFACE_ROOT: "/var/data/models/insightface",
      RAILWAY_RUN_UID: "0",
    },
    volumeMounts: {
      "/var/data": data,
    },
  });

  return project("asistencia-ia", {
    resources: [api, data],
  });
});
