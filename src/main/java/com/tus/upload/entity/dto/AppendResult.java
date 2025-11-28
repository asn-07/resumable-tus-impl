package com.tus.upload.entity.dto;

import com.tus.upload.entity.V2TusUpload;

public record AppendResult(V2TusUpload upload, String assetId) {
}