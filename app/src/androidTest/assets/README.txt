Synthetic geometric images for offline UI screenshots.
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0

The PNGs are generated geometric patterns without personal data or metadata.
The test creates JPEG copies in MediaStore and writes only EXIF orientation on
one copy at runtime, to exercise rotated image input without committing metadata.
