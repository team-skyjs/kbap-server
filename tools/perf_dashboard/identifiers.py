import re
from dataclasses import dataclass
from typing import Final

from .models import CampaignId


CAMPAIGN_ID_PATTERN: Final = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


@dataclass(frozen=True, slots=True)
class InvalidCampaignIdError(Exception):
    campaign_id: str

    def __str__(self) -> str:
        return "invalid-campaign-id"


def parse_campaign_id(raw: str) -> CampaignId:
    if raw in (".", "..") or CAMPAIGN_ID_PATTERN.fullmatch(raw) is None:
        raise InvalidCampaignIdError(raw)
    return CampaignId(raw)
