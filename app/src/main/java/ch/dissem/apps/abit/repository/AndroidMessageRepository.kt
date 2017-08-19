/*
 * Copyright 2015 Christian Basler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.dissem.apps.abit.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import ch.dissem.apps.abit.util.Labels
import ch.dissem.apps.abit.util.UuidUtils
import ch.dissem.apps.abit.util.UuidUtils.asUuid
import ch.dissem.bitmessage.entity.BitmessageAddress
import ch.dissem.bitmessage.entity.Plaintext
import ch.dissem.bitmessage.entity.valueobject.InventoryVector
import ch.dissem.bitmessage.entity.valueobject.Label
import ch.dissem.bitmessage.ports.AbstractMessageRepository
import ch.dissem.bitmessage.ports.MessageRepository
import ch.dissem.bitmessage.utils.Encode
import ch.dissem.bitmessage.utils.Strings.hex
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.*

/**
 * [MessageRepository] implementation using the Android SQL API.
 */
class AndroidMessageRepository(private val sql: SqlHelper, private val context: Context) : AbstractMessageRepository() {

    override fun findMessages(label: Label?): List<Plaintext> {
        if (label === LABEL_ARCHIVE) {
            return super.findMessages(null as Label?)
        } else {
            return super.findMessages(label)
        }
    }

    fun findMessageIds(label: Label): List<Long> {
        if (label === LABEL_ARCHIVE) {
            return findIds("id NOT IN (SELECT message_id FROM Message_Label)")
        } else {
            return findIds("id IN (SELECT message_id FROM Message_Label WHERE label_id=" + label.id + ")")
        }
    }

    public override fun findLabels(where: String): List<Label> {
        val result = LinkedList<Label>()

        // Define a projection that specifies which columns from the database
        // you will actually use after this query.
        val projection = arrayOf(LBL_COLUMN_ID, LBL_COLUMN_LABEL, LBL_COLUMN_TYPE, LBL_COLUMN_COLOR)

        val db = sql.readableDatabase
        db.query(
                LBL_TABLE_NAME, projection,
                where, null, null, null,
                LBL_COLUMN_ORDER
        ).use { c ->
            while (c.moveToNext()) {
                result.add(getLabel(c))
            }
        }
        return result
    }

    private fun getLabel(c: Cursor): Label {
        val typeName = c.getString(c.getColumnIndex(LBL_COLUMN_TYPE))
        val type = if (typeName == null) null else Label.Type.valueOf(typeName)
        val text: String? = Labels.getText(type, null, context)
        val label = Label(
                text ?: c.getString(c.getColumnIndex(LBL_COLUMN_LABEL)),
                type,
                c.getInt(c.getColumnIndex(LBL_COLUMN_COLOR)))
        label.id = c.getLong(c.getColumnIndex(LBL_COLUMN_ID))
        return label
    }

    override fun save(label: Label) {
        val db = sql.writableDatabase
        if (label.id != null) {
            val values = ContentValues()
            values.put(LBL_COLUMN_LABEL, label.toString())
            values.put(LBL_COLUMN_TYPE, label.type?.name)
            values.put(LBL_COLUMN_COLOR, label.color)
            values.put(LBL_COLUMN_ORDER, label.ord)
            db.update(LBL_TABLE_NAME, values, "id=?", arrayOf(label.id.toString()))
        } else {
            try {
                db.beginTransaction()

                val exists = DatabaseUtils.queryNumEntries(db, LBL_TABLE_NAME, "label=?", arrayOf(label.toString())) > 0

                if (exists) {
                    val values = ContentValues()
                    values.put(LBL_COLUMN_TYPE, label.type?.name)
                    values.put(LBL_COLUMN_COLOR, label.color)
                    values.put(LBL_COLUMN_ORDER, label.ord)
                    db.update(LBL_TABLE_NAME, values, "label=?", arrayOf(label.toString()))
                } else {
                    val values = ContentValues()
                    values.put(LBL_COLUMN_LABEL, label.toString())
                    values.put(LBL_COLUMN_TYPE, label.type?.name)
                    values.put(LBL_COLUMN_COLOR, label.color)
                    values.put(LBL_COLUMN_ORDER, label.ord)
                    db.insertOrThrow(LBL_TABLE_NAME, null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun countUnread(label: Label?): Int {
        if (label === LABEL_ARCHIVE) {
            return 0
        } else if (label == null) {
            return DatabaseUtils.queryNumEntries(
                    sql.readableDatabase,
                    TABLE_NAME,
                    "id IN (SELECT message_id FROM Message_Label WHERE label_id IN (SELECT id FROM Label WHERE type=?))",
                    arrayOf(Label.Type.UNREAD.name)
            ).toInt()
        } else {
            return DatabaseUtils.queryNumEntries(
                    sql.readableDatabase,
                    TABLE_NAME,
                    "        id IN (SELECT message_id FROM Message_Label WHERE label_id=?) " + "AND id IN (SELECT message_id FROM Message_Label WHERE label_id IN (SELECT id FROM Label WHERE type=?))",
                    arrayOf(label.id.toString(), Label.Type.UNREAD.name)
            ).toInt()
        }
    }

    override fun findConversations(label: Label?): List<UUID> {
        val projection = arrayOf(COLUMN_CONVERSATION)

        val where: String
        if (label == null) {
            where = "id NOT IN (SELECT message_id FROM Message_Label)"
        } else {
            where = "id IN (SELECT message_id FROM Message_Label WHERE label_id=" + label.id + ")"
        }
        val result = LinkedList<UUID>()
        val db = sql.readableDatabase
        db.query(
                TABLE_NAME, projection,
                where, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                val uuidBytes = c.getBlob(c.getColumnIndex(COLUMN_CONVERSATION))
                result.add(asUuid(uuidBytes))
            }
        }
        return result
    }


    private fun updateParents(db: SQLiteDatabase, message: Plaintext) {
        if (message.inventoryVector == null || message.parents.isEmpty()) {
            // There are no parents to save yet (they are saved in the extended data, that's enough for now)
            return
        }
        val childIV = message.inventoryVector!!.hash
        db.delete(PARENTS_TABLE_NAME, "child=?", arrayOf(hex(childIV)))

        // save new parents
        var order = 0
        for (parentIV in message.parents) {
            val parent = getMessage(parentIV)
            if (parent != null) {
                mergeConversations(db, parent.conversationId, message.conversationId)
                order++
                val values = ContentValues()
                values.put("parent", parentIV.hash)
                values.put("child", childIV)
                values.put("pos", order)
                values.put("conversation", UuidUtils.asBytes(message.conversationId))
                db.insertOrThrow(PARENTS_TABLE_NAME, null, values)
            }
        }
    }

    /**
     * Replaces every occurrence of the source conversation ID with the target ID

     * @param db     is used to keep everything within one transaction
     * *
     * @param source ID of the conversation to be merged
     * *
     * @param target ID of the merge target
     */
    private fun mergeConversations(db: SQLiteDatabase, source: UUID, target: UUID) {
        val values = ContentValues()
        values.put("conversation", UuidUtils.asBytes(target))
        val whereArgs = arrayOf(hex(UuidUtils.asBytes(source)))
        db.update(TABLE_NAME, values, "conversation=?", whereArgs)
        db.update(PARENTS_TABLE_NAME, values, "conversation=?", whereArgs)
    }

    private fun findIds(where: String): List<Long> {
        val result = LinkedList<Long>()

        // Define a projection that specifies which columns from the database
        // you will actually use after this query.
        val projection = arrayOf(COLUMN_ID)

        val db = sql.readableDatabase
        db.query(
                TABLE_NAME, projection,
                where, null, null, null,
                "$COLUMN_RECEIVED DESC, $COLUMN_SENT DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                result.add(id)
            }
        }
        return result
    }

    override fun find(where: String): List<Plaintext> {
        val result = LinkedList<Plaintext>()

        // Define a projection that specifies which columns from the database
        // you will actually use after this query.
        val projection = arrayOf(COLUMN_ID, COLUMN_IV, COLUMN_TYPE, COLUMN_SENDER, COLUMN_RECIPIENT, COLUMN_DATA, COLUMN_ACK_DATA, COLUMN_SENT, COLUMN_RECEIVED, COLUMN_STATUS, COLUMN_TTL, COLUMN_RETRIES, COLUMN_NEXT_TRY, COLUMN_CONVERSATION)

        val db = sql.readableDatabase
        db.query(
                TABLE_NAME, projection,
                where, null, null, null,
                "$COLUMN_RECEIVED DESC, $COLUMN_SENT DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val iv = c.getBlob(c.getColumnIndex(COLUMN_IV))
                val data = c.getBlob(c.getColumnIndex(COLUMN_DATA))
                val type = Plaintext.Type.valueOf(c.getString(c.getColumnIndex(COLUMN_TYPE)))
                val builder = Plaintext.readWithoutSignature(type,
                        ByteArrayInputStream(data))
                val id = c.getLong(c.getColumnIndex(COLUMN_ID))
                builder.id(id)
                builder.IV(InventoryVector.fromHash(iv))
                val sender = c.getString(c.getColumnIndex(COLUMN_SENDER))
                if (sender != null) {
                    val address = ctx.addressRepository.getAddress(sender)
                    if (address != null) {
                        builder.from(address)
                    } else {
                        builder.from(BitmessageAddress(sender))
                    }
                }
                val recipient = c.getString(c.getColumnIndex(COLUMN_RECIPIENT))
                if (recipient != null) {
                    val address = ctx.addressRepository.getAddress(recipient)
                    if (address != null) {
                        builder.to(address)
                    } else {
                        builder.to(BitmessageAddress(sender))
                    }
                }
                builder.ackData(c.getBlob(c.getColumnIndex(COLUMN_ACK_DATA)))
                builder.sent(c.getLong(c.getColumnIndex(COLUMN_SENT)))
                builder.received(c.getLong(c.getColumnIndex(COLUMN_RECEIVED)))
                builder.status(Plaintext.Status.valueOf(c.getString(c.getColumnIndex(COLUMN_STATUS))))
                builder.ttl(c.getLong(c.getColumnIndex(COLUMN_TTL)))
                builder.retries(c.getInt(c.getColumnIndex(COLUMN_RETRIES)))
                val nextTryColumn = c.getColumnIndex(COLUMN_NEXT_TRY)
                if (!c.isNull(nextTryColumn)) {
                    builder.nextTry(c.getLong(nextTryColumn))
                }
                builder.conversation(asUuid(c.getBlob(c.getColumnIndex(COLUMN_CONVERSATION))))
                builder.labels(findLabels(id))
                result.add(builder.build())
            }
        }
        return result
    }

    private fun findLabels(id: Long) = findLabels("id IN (SELECT label_id FROM Message_Label WHERE message_id=$id)")

    override fun save(message: Plaintext) {
        saveContactIfNecessary(message.from)
        saveContactIfNecessary(message.to)
        val db = sql.writableDatabase
        try {
            db.beginTransaction()

            // save message
            if (message.id == null) {
                insert(db, message)
            } else {
                update(db, message)
            }

            updateParents(db, message)

            // remove existing labels
            db.delete(JOIN_TABLE_NAME, "message_id=?", arrayOf(message.id.toString()))

            // save labels
            val values = ContentValues()
            for (label in message.labels) {
                values.put(JT_COLUMN_LABEL, label.id as Long?)
                values.put(JT_COLUMN_MESSAGE, message.id as Long?)
                db.insertOrThrow(JOIN_TABLE_NAME, null, values)
            }
            db.setTransactionSuccessful()
        } catch (e: SQLiteConstraintException) {
            LOG.trace(e.message, e)
        } finally {
            db.endTransaction()
        }
    }

    private fun getValues(message: Plaintext): ContentValues {
        val values = ContentValues()
        values.put(COLUMN_IV, if (message.inventoryVector == null)
            null
        else
            message
                    .inventoryVector!!.hash)
        values.put(COLUMN_TYPE, message.type.name)
        values.put(COLUMN_SENDER, message.from.address)
        values.put(COLUMN_RECIPIENT, if (message.to == null) null else message.to!!.address)
        values.put(COLUMN_DATA, Encode.bytes(message))
        values.put(COLUMN_ACK_DATA, message.ackData)
        values.put(COLUMN_SENT, message.sent)
        values.put(COLUMN_RECEIVED, message.received)
        values.put(COLUMN_STATUS, message.status.name)
        values.put(COLUMN_INITIAL_HASH, message.initialHash)
        values.put(COLUMN_TTL, message.ttl)
        values.put(COLUMN_RETRIES, message.retries)
        values.put(COLUMN_NEXT_TRY, message.nextTry)
        values.put(COLUMN_CONVERSATION, UuidUtils.asBytes(message.conversationId))
        return values
    }

    private fun insert(db: SQLiteDatabase, message: Plaintext) {
        val id = db.insertOrThrow(TABLE_NAME, null, getValues(message))
        message.id = id
    }

    private fun update(db: SQLiteDatabase, message: Plaintext) {
        db.update(TABLE_NAME, getValues(message), "id=?", arrayOf(message.id.toString()))
    }

    override fun remove(message: Plaintext) {
        val db = sql.writableDatabase
        db.delete(TABLE_NAME, "id = " + message.id!!, null)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(AndroidMessageRepository::class.java)

        @JvmField
        val LABEL_ARCHIVE = Label("archive", null, 0)

        private const val TABLE_NAME = "Message"
        private const val COLUMN_ID = "id"
        private const val COLUMN_IV = "iv"
        private const val COLUMN_TYPE = "type"
        private const val COLUMN_SENDER = "sender"
        private const val COLUMN_RECIPIENT = "recipient"
        private const val COLUMN_DATA = "data"
        private const val COLUMN_ACK_DATA = "ack_data"
        private const val COLUMN_SENT = "sent"
        private const val COLUMN_RECEIVED = "received"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_TTL = "ttl"
        private const val COLUMN_RETRIES = "retries"
        private const val COLUMN_NEXT_TRY = "next_try"
        private const val COLUMN_INITIAL_HASH = "initial_hash"
        private const val COLUMN_CONVERSATION = "conversation"

        private const val PARENTS_TABLE_NAME = "Message_Parent"

        private const val JOIN_TABLE_NAME = "Message_Label"
        private const val JT_COLUMN_MESSAGE = "message_id"
        private const val JT_COLUMN_LABEL = "label_id"

        private const val LBL_TABLE_NAME = "Label"
        private const val LBL_COLUMN_ID = "id"
        private const val LBL_COLUMN_LABEL = "label"
        private const val LBL_COLUMN_TYPE = "type"
        private const val LBL_COLUMN_COLOR = "color"
        private const val LBL_COLUMN_ORDER = "ord"
    }
}