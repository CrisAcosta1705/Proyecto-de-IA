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
      ASISTENCIA_DATA_ENCRYPTION_KEY: preserve(),
      ASISTENCIA_DATA_ENCRYPTION_KEY_PREVIOUS: preserve(),
      ASISTENCIA_DB: "/var/data/asistencia.db",
      ARC_FACE_MODEL_PATH: "/app/models/w600k_mbf.onnx",
      FACE_MINIMUM_REFERENCE_SUPPORT: "0.38",
      FACE_MINIMUM_SCORE_MARGIN: "0.05",
      FACE_SECOND_REFERENCE_WEIGHT: "0.30",
      FACE_SIMILARITY_THRESHOLD: "0.50",
      FACE_SINGLE_REFERENCE_THRESHOLD: "0.58",
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
