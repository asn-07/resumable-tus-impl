package com.tus.upload.repo;

import com.tus.upload.common.entity.AssetExif;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AssetExifNativeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public void upsertExif(AssetExif exif) {
        String sql = """
            INSERT INTO asset_exif (
                asset_id, description, exif_image_width, exif_image_height, file_size_in_byte,
                orientation, date_time_original, modify_date, time_zone,
                latitude, longitude, projection_type, city, live_photo_cid,
                auto_stack_id, state, country, make, model, lens_model,
                f_number, focal_length, iso, exposure_time,
                profile_description, colorspace, bits_per_sample, rating, fps, update_id
            )
            VALUES (
                :assetId, :description, :exifImageWidth, :exifImageHeight, :fileSizeInByte,
                :orientation, :dateTimeOriginal, :modifyDate, :timeZone,
                :latitude, :longitude, :projectionType, :city, :livePhotoCID,
                :autoStackId, :state, :country, :make, :model, :lensModel,
                :fNumber, :focalLength, :iso, :exposureTime,
                :profileDescription, :colorspace, :bitsPerSample, :rating, :fps, :updateId
            )
            ON CONFLICT (asset_id) DO UPDATE SET
                description = EXCLUDED.description,
                exif_image_width = EXCLUDED.exif_image_width,
                exif_image_height = EXCLUDED.exif_image_height,
                file_size_in_byte = EXCLUDED.file_size_in_byte,
                orientation = EXCLUDED.orientation,
                date_time_original = EXCLUDED.date_time_original,
                modify_date = EXCLUDED.modify_date,
                time_zone = EXCLUDED.time_zone,
                latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude,
                projection_type = EXCLUDED.projection_type,
                city = EXCLUDED.city,
                live_photo_cid = EXCLUDED.live_photo_cid,
                auto_stack_id = EXCLUDED.auto_stack_id,
                state = EXCLUDED.state,
                country = EXCLUDED.country,
                make = EXCLUDED.make,
                model = EXCLUDED.model,
                lens_model = EXCLUDED.lens_model,
                f_number = EXCLUDED.f_number,
                focal_length = EXCLUDED.focal_length,
                iso = EXCLUDED.iso,
                exposure_time = EXCLUDED.exposure_time,
                profile_description = EXCLUDED.profile_description,
                colorspace = EXCLUDED.colorspace,
                bits_per_sample = EXCLUDED.bits_per_sample,
                rating = EXCLUDED.rating,
                fps = EXCLUDED.fps,
                update_id = EXCLUDED.update_id
            """;

        jdbcTemplate.update(sql, new BeanPropertySqlParameterSource(exif));
    }
}
