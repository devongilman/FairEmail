package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2021 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.mail.Part;

import static androidx.room.ForeignKey.CASCADE;

@Entity(
        tableName = EntityAttachment.TABLE_NAME,
        foreignKeys = {
                @ForeignKey(childColumns = "message", entity = EntityMessage.class, parentColumns = "id", onDelete = CASCADE)
        },
        indices = {
                @Index(value = {"message"}),
                @Index(value = {"message", "sequence", "subsequence"}, unique = true),
                @Index(value = {"message", "cid"})
        }
)
public class EntityAttachment {
    static final String TABLE_NAME = "attachment";

    static final Integer PGP_MESSAGE = 1;
    static final Integer PGP_SIGNATURE = 2;
    static final Integer PGP_KEY = 3;
    static final Integer PGP_CONTENT = 4;
    static final Integer SMIME_MESSAGE = 5;
    static final Integer SMIME_SIGNATURE = 6;
    static final Integer SMIME_SIGNED_DATA = 7;
    static final Integer SMIME_CONTENT = 8;

    @PrimaryKey(autoGenerate = true)
    public Long id;
    @NonNull
    public Long message;
    @NonNull
    public Integer sequence;
    public Integer subsequence; // embedded messages
    public String name;
    @NonNull
    public String type;
    public String disposition;
    public String cid; // Content-ID
    public Integer encryption;
    public Long size;
    public Integer progress;
    @NonNull
    public Boolean available = false;
    public String error;

    // Gmail sends inline images as attachments with a name and cid

    boolean isInline() {
        return (Part.INLINE.equals(disposition) || cid != null);
    }

    boolean isAttachment() {
        return (Part.ATTACHMENT.equals(disposition) || !TextUtils.isEmpty(name));
    }

    boolean isImage() {
        return Helper.isImage(getMimeType());
    }

    boolean isEncryption() {
        return (encryption != null);
    }

    File getFile(Context context) {
        return getFile(context, id, name);
    }

    static File getFile(Context context, long id, String name) {
        File dir = new File(context.getFilesDir(), "attachments");
        if (!dir.exists())
            dir.mkdir();
        String filename = Long.toString(id);
        if (!TextUtils.isEmpty(name))
            filename += "." + Helper.sanitizeFilename(name);
        if (filename.length() > 127)
            filename = filename.substring(0, 127);
        return new File(dir, filename);
    }

    static void copy(Context context, long oldid, long newid) {
        DB db = DB.getInstance(context);

        List<EntityAttachment> attachments = db.attachment().getAttachments(oldid);
        for (EntityAttachment attachment : attachments) {
            File source = attachment.getFile(context);

            attachment.id = null;
            attachment.message = newid;
            attachment.progress = null;
            attachment.id = db.attachment().insertAttachment(attachment);

            if (attachment.available) {
                File target = attachment.getFile(context);
                try {
                    Helper.copy(source, target);
                } catch (IOException ex) {
                    Log.e(ex);
                    db.attachment().setError(attachment.id, Log.formatThrowable(ex, false));
                }
            }
        }
    }

    String getMimeType() {
        // Try to guess a better content type
        // For example, sometimes PDF files are sent as application/octet-stream
        // https://android.googlesource.com/platform/libcore/+/refs/tags/android-9.0.0_r49/luni/src/main/java/libcore/net/MimeUtils.java
        // https://blogs.msdn.microsoft.com/vsofficedeveloper/2008/05/08/office-2007-file-format-mime-types-for-http-content-streaming-2/

        if (encryption != null)
            return type;

        String extension = Helper.getExtension(name);
        if (extension == null)
            return type;

        extension = extension.toLowerCase(Locale.ROOT);

        // Fix types
        if ("gpx".equals(extension))
            return "application/gpx+xml";

        if ("pdf".equals(extension))
            return "application/pdf";

        if ("text/plain".equals(type) && "ics".equals(extension))
            return "text/calendar";

        if ("text/plain".equals(type) && "ovpn".equals(extension))
            return "application/x-openvpn-profile";

        // https://www.rfc-editor.org/rfc/rfc3555.txt
        if ("video/jpeg".equals(type))
            return "image/jpeg";

        if (!TextUtils.isEmpty(type) &&
                (type.endsWith("/pdf") || type.endsWith("/x-pdf")))
            return "application/pdf";

        if ("application/vnd.ms-pps".equals(type))
            return "application/vnd.ms-powerpoint";

        // Guess types
        String gtype = Helper.guessMimeType(name);
        if (gtype != null) {
            if (TextUtils.isEmpty(type) ||
                    "*/*".equals(type) ||
                    type.startsWith("unknown/") ||
                    type.endsWith("/unknown") ||
                    "application/octet-stream".equals(type) ||
                    "application/zip".equals(type))
                return gtype;

            // Some servers erroneously remove dots from mime types
            if (gtype.replace(".", "").equals(type))
                return gtype;
        }

        return type;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof EntityAttachment) {
            EntityAttachment other = (EntityAttachment) obj;
            return (this.message.equals(other.message) &&
                    this.sequence.equals(other.sequence) &&
                    Objects.equals(this.name, other.name) &&
                    this.type.equals(other.type) &&
                    Objects.equals(this.disposition, other.disposition) &&
                    Objects.equals(this.cid, other.cid) &&
                    Objects.equals(this.encryption, other.encryption) &&
                    Objects.equals(this.size, other.size) &&
                    Objects.equals(this.progress, other.progress) &&
                    this.available.equals(other.available) &&
                    Objects.equals(this.error, other.error));
        } else
            return false;
    }

    @NonNull
    @Override
    public String toString() {
        return (this.name +
                " type=" + this.type +
                " disposition=" + this.disposition +
                " cid=" + this.cid +
                " encryption=" + this.encryption +
                " size=" + this.size);
    }
}
