from dataclasses import asdict, dataclass


PROFILE_ID = "arcface_w600k_r50_112x112_v1"
MODEL_ID = PROFILE_ID
MODEL_FILENAME = "w600k_r50.onnx"
MODEL_SHA256 = "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43"
DETECTOR_ID = "scrfd_det_10g_960"
INPUT_SIZE = 112
EMBEDDING_SIZE = 512
SIMILARITY_THRESHOLD = 0.48
SINGLE_REFERENCE_THRESHOLD = 0.55
MINIMUM_SCORE_MARGIN = 0.07
MINIMUM_REFERENCE_SUPPORT = 0.30
SECOND_REFERENCE_WEIGHT = 0.20

MOBILE_PROFILE = {
    "profile_id": "arcface_w600k_mbf_112x112_v3",
    "model_sha256": "9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f",
    "input_size": 112,
    "embedding_size": 512,
    "role": "offline_fallback",
}


@dataclass(frozen=True)
class RecognitionProfile:
    profile_id: str
    model_sha256: str
    input_size: int
    embedding_size: int
    similarity_threshold: float
    single_reference_threshold: float
    minimum_score_margin: float
    minimum_reference_support: float
    second_reference_weight: float
    detector_id: str = DETECTOR_ID
    role: str = "canonical_server"

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def recognition_profile(
    similarity_threshold: float = SIMILARITY_THRESHOLD,
    single_reference_threshold: float = SINGLE_REFERENCE_THRESHOLD,
    minimum_score_margin: float = MINIMUM_SCORE_MARGIN,
    minimum_reference_support: float = MINIMUM_REFERENCE_SUPPORT,
    second_reference_weight: float = SECOND_REFERENCE_WEIGHT,
) -> RecognitionProfile:
    return RecognitionProfile(
        profile_id=PROFILE_ID,
        model_sha256=MODEL_SHA256,
        input_size=INPUT_SIZE,
        embedding_size=EMBEDDING_SIZE,
        similarity_threshold=similarity_threshold,
        single_reference_threshold=single_reference_threshold,
        minimum_score_margin=minimum_score_margin,
        minimum_reference_support=minimum_reference_support,
        second_reference_weight=second_reference_weight,
    )
