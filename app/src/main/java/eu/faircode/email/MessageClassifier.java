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
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

public class MessageClassifier {
    private static boolean loaded = false;
    private static boolean dirty = false;
    private static final Map<Long, Map<String, Integer>> classMessages = new HashMap<>();
    private static final Map<Long, Map<String, Map<String, Integer>>> wordClassFrequency = new HashMap<>();

    private static final int MIN_MATCHED_WORDS = 10;
    private static final double CHANCE_THRESHOLD = 2.0;

    static void classify(EntityMessage message, EntityFolder folder, EntityFolder target, Context context) {
        try {
            if (!isEnabled(context))
                return;

            if (!canClassify(folder.type))
                return;

            if (target != null && !canClassify(target.type))
                return;

            File file = message.getFile(context);
            if (!file.exists())
                return;

            // Build text to classify
            StringBuilder sb = new StringBuilder();

            List<Address> addresses = new ArrayList<>();
            if (message.from != null)
                addresses.addAll(Arrays.asList(message.from));
            if (message.to != null)
                addresses.addAll(Arrays.asList(message.to));
            if (message.cc != null)
                addresses.addAll(Arrays.asList(message.cc));
            if (message.bcc != null)
                addresses.addAll(Arrays.asList(message.bcc));
            if (message.reply != null)
                addresses.addAll(Arrays.asList(message.reply));

            for (Address address : addresses) {
                String email = ((InternetAddress) address).getAddress();
                String name = ((InternetAddress) address).getAddress();
                if (!TextUtils.isEmpty(email)) {
                    sb.append(email).append('\n');
                    int at = email.indexOf('@');
                    String domain = (at < 0 ? null : email.substring(at + 1));
                    if (!TextUtils.isEmpty(domain))
                        sb.append(domain).append('\n');
                }
                if (!TextUtils.isEmpty(name))
                    sb.append(name).append('\n');
            }

            if (message.subject != null)
                sb.append(message.subject).append('\n');

            sb.append(HtmlHelper.getFullText(file));

            if (sb.length() == 0)
                return;

            // Load data if needed
            load(context);

            // Initialize data if needed
            if (!classMessages.containsKey(folder.account))
                classMessages.put(folder.account, new HashMap<>());
            if (!wordClassFrequency.containsKey(folder.account))
                wordClassFrequency.put(folder.account, new HashMap<>());

            // Classify text
            String classified = classify(folder.account, folder.name, sb.toString(), target == null, context);

            EntityLog.log(context, "Classifier" +
                    " folder=" + folder.name +
                    " message=" + message.id +
                    "@" + new Date(message.received) +
                    ":" + message.subject +
                    " class=" + classified +
                    " re=" + message.auto_classified);

            // Update message count
            Integer m = classMessages.get(folder.account).get(folder.name);
            if (target == null) {
                m = (m == null ? 1 : m + 1);
                classMessages.get(folder.account).put(folder.name, m);
            } else {
                if (m != null && m > 0)
                    classMessages.get(folder.account).put(folder.name, m - 1);
            }
            EntityLog.log(context, "Classifier classify=" + folder.name +
                    " messages=" + classMessages.get(folder.account).get(folder.name));

            dirty = true;

            // Auto classify
            if (classified != null &&
                    !classified.equals(folder.name) &&
                    !message.auto_classified &&
                    !EntityFolder.JUNK.equals(folder.type)) {
                DB db = DB.getInstance(context);
                try {
                    db.beginTransaction();

                    EntityFolder dest = db.folder().getFolderByName(folder.account, classified);
                    if (dest != null && dest.auto_classify) {
                        EntityOperation.queue(context, message, EntityOperation.MOVE, dest.id, false, true);
                        message.ui_hide = true;
                    }

                    db.setTransactionSuccessful();

                } finally {
                    db.endTransaction();
                }
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    private static String classify(long account, String classify, String text, boolean added, Context context) {
        int maxMatchedWords = 0;
        List<String> words = new ArrayList<>();
        Map<String, Stat> classStats = new HashMap<>();

        BreakIterator boundary = BreakIterator.getWordInstance(); // TODO ICU
        boundary.setText(text);
        int start = boundary.first();
        for (int end = boundary.next(); end != BreakIterator.DONE; end = boundary.next()) {
            String word = text.substring(start, end).toLowerCase();
            if (word.length() > 1 &&
                    !words.contains(word) &&
                    !word.matches(".*\\d.*")) {
                words.add(word);

                Map<String, Integer> classFrequency = wordClassFrequency.get(account).get(word);
                if (added) {
                    if (classFrequency == null) {
                        classFrequency = new HashMap<>();
                        wordClassFrequency.get(account).put(word, classFrequency);
                    }

                    for (String clazz : classFrequency.keySet()) {
                        int frequency = classFrequency.get(clazz);

                        Stat stat = classStats.get(clazz);
                        if (stat == null) {
                            stat = new Stat();
                            classStats.put(clazz, stat);
                        }

                        stat.matchedWords++;
                        stat.totalFrequency += frequency;

                        if (stat.matchedWords > maxMatchedWords)
                            maxMatchedWords = stat.matchedWords;
                    }

                    Integer c = classFrequency.get(classify);
                    c = (c == null ? 1 : c + 1);
                    classFrequency.put(classify, c);
                } else {
                    Integer c = (classFrequency == null ? null : classFrequency.get(classify));
                    if (c != null)
                        if (c > 0)
                            classFrequency.put(classify, c - 1);
                        else
                            classFrequency.remove(classify);
                }
            }
            start = end;
        }

        if (!added)
            return null;

        if (maxMatchedWords == 0)
            return null;

        DB db = DB.getInstance(context);
        List<Chance> chances = new ArrayList<>();
        for (String clazz : classStats.keySet()) {
            Integer messages = classMessages.get(account).get(clazz);
            if (messages == null || messages == 0) {
                Log.w("Classifier no messages class=" + account + ":" + clazz);
                continue;
            }

            EntityFolder folder = db.folder().getFolderByName(account, clazz);
            if (folder == null) {
                Log.w("Classifier no folder class=" + account + ":" + clazz);
                continue;
            }

            Stat stat = classStats.get(clazz);
            double chance = (double) stat.totalFrequency / messages / maxMatchedWords;
            Chance c = new Chance(clazz, chance);
            EntityLog.log(context, "Classifier " + c +
                    " frequency=" + stat.totalFrequency + "/" + messages +
                    " matched=" + stat.matchedWords + "/" + maxMatchedWords);
            chances.add(c);
        }

        if (BuildConfig.DEBUG)
            Log.i("Classifier words=" + TextUtils.join(", ", words));

        if (maxMatchedWords < MIN_MATCHED_WORDS)
            return null;
        if (chances.size() <= 1)
            return null;

        Collections.sort(chances, new Comparator<Chance>() {
            @Override
            public int compare(Chance c1, Chance c2) {
                return -c1.chance.compareTo(c2.chance);
            }
        });

        String classification = null;
        if (chances.get(0).chance / chances.get(1).chance >= CHANCE_THRESHOLD)
            classification = chances.get(0).clazz;

        Log.i("Classifier classify=" + classify + " classified=" + classification);

        return classification;
    }

    static synchronized void save(Context context) throws JSONException, IOException {
        if (!dirty)
            return;

        File file = getFile(context);
        Helper.writeText(file, toJson().toString(2));

        dirty = false;
        Log.i("Classifier data saved");
    }

    private static synchronized void load(Context context) throws IOException, JSONException {
        if (loaded || dirty)
            return;

        classMessages.clear();
        wordClassFrequency.clear();

        File file = getFile(context);
        if (file.exists()) {
            String json = Helper.readText(file);
            fromJson(new JSONObject(json));
        }

        loaded = true;
        Log.i("Classifier data loaded");
    }

    static synchronized void clear(Context context) {
        classMessages.clear();
        wordClassFrequency.clear();
        dirty = true;
        Log.i("Classifier data cleared");
    }

    static boolean isEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("classification", false);
    }

    static boolean canClassify(String folderType) {
        return EntityFolder.INBOX.equals(folderType) ||
                EntityFolder.JUNK.equals(folderType) ||
                EntityFolder.USER.equals(folderType);
    }

    static File getFile(Context context) {
        return new File(context.getFilesDir(), "classifier.json");
    }

    static JSONObject toJson() throws JSONException {
        JSONArray jmessages = new JSONArray();
        for (Long account : classMessages.keySet())
            for (String clazz : classMessages.get(account).keySet()) {
                JSONObject jmessage = new JSONObject();
                jmessage.put("account", account);
                jmessage.put("class", clazz);
                jmessage.put("count", classMessages.get(account).get(clazz));
                jmessages.put(jmessage);
            }

        JSONArray jwords = new JSONArray();
        for (Long account : classMessages.keySet())
            for (String word : wordClassFrequency.get(account).keySet()) {
                Map<String, Integer> classFrequency = wordClassFrequency.get(account).get(word);
                for (String clazz : classFrequency.keySet()) {
                    JSONObject jword = new JSONObject();
                    jword.put("account", account);
                    jword.put("word", word);
                    jword.put("class", clazz);
                    jword.put("frequency", classFrequency.get(clazz));
                    jwords.put(jword);
                }
            }

        JSONObject jroot = new JSONObject();
        jroot.put("messages", jmessages);
        jroot.put("words", jwords);

        return jroot;
    }

    static void fromJson(JSONObject jroot) throws JSONException {
        JSONArray jmessages = jroot.getJSONArray("messages");
        for (int m = 0; m < jmessages.length(); m++) {
            JSONObject jmessage = (JSONObject) jmessages.get(m);
            long account = jmessage.getLong("account");
            if (!classMessages.containsKey(account))
                classMessages.put(account, new HashMap<>());
            classMessages.get(account).put(jmessage.getString("class"), jmessage.getInt("count"));
        }

        JSONArray jwords = jroot.getJSONArray("words");
        for (int w = 0; w < jwords.length(); w++) {
            JSONObject jword = (JSONObject) jwords.get(w);
            long account = jword.getLong("account");
            if (!wordClassFrequency.containsKey(account))
                wordClassFrequency.put(account, new HashMap<>());
            String word = jword.getString("word");
            Map<String, Integer> classFrequency = wordClassFrequency.get(account).get(word);
            if (classFrequency == null) {
                classFrequency = new HashMap<>();
                wordClassFrequency.get(account).put(word, classFrequency);
            }
            classFrequency.put(jword.getString("class"), jword.getInt("frequency"));
        }
    }

    private static class Stat {
        int matchedWords = 0;
        int totalFrequency = 0;
    }

    private static class Chance {
        String clazz;
        Double chance;

        Chance(String clazz, Double chance) {
            this.clazz = clazz;
            this.chance = chance;
        }

        @NotNull
        @Override
        public String toString() {
            return clazz + "=" + chance;
        }
    }
}
