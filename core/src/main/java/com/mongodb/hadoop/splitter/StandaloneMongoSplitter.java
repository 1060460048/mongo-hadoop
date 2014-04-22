/*
 * Copyright 2010-2013 10gen Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hadoop.splitter;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBObject;
import com.mongodb.MongoURI;
import com.mongodb.hadoop.input.MongoInputSplit;
import com.mongodb.hadoop.util.MongoConfigUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;

import java.util.ArrayList;
import java.util.List;


/* This class is an implementation of MongoSplitter which
 * calculates a list of splits on a single collection
 * by running the MongoDB internal command "splitVector",
 * which generates a list of index boundary pairs, each 
 * containing an approximate amount of data depending on the
 * max chunk size used, and converting those index boundaries
 * into splits.
 *
 * This splitter is the default implementation used for any
 * collection which is not sharded.
 *
 */
public class StandaloneMongoSplitter extends MongoCollectionSplitter {

    private static final Log LOG = LogFactory.getLog(StandaloneMongoSplitter.class);

    public StandaloneMongoSplitter(final Configuration conf) {
        super(conf);
    }

    // Generate one split per chunk.
    @Override
    public List<InputSplit> calculateSplits() throws SplitFailedException {
        this.init();
        final DBObject splitKey = MongoConfigUtil.getInputSplitKey(conf);
        final int splitSize = MongoConfigUtil.getSplitSize(conf);

        final ArrayList<InputSplit> returnVal = new ArrayList<InputSplit>();
        final String ns = this.inputCollection.getFullName();

        MongoURI inputURI = MongoConfigUtil.getInputURI(conf);

        LOG.info("Running splitvector to check splits against " + inputURI);
        final DBObject cmd = BasicDBObjectBuilder.start("splitVector", ns)
                                 .add("keyPattern", splitKey)
                                      // force:True is misbehaving it seems
                                 .add("force", false)
                                 .add("maxChunkSize", splitSize)
                                 .get();

        CommandResult data;
        if (this.authDB == null) {
            DB adminDB = this.inputCollection.getDB().getSisterDB("admin");
            
            if(!adminDB.isAuthenticated() && 
                    inputURI.getUsername() != null && inputURI.getPassword() != null) {
                if (!adminDB.authenticate(inputURI.getUsername(), inputURI.getPassword())) {
                    throw new SplitFailedException("Could not authenticate to admin database.  Try setting mongo.auth.uri with admin credentials.");
                };
            }
            data = adminDB.command(cmd);
        } else {
            data = this.authDB.command(cmd);
        }

        if (data.containsField("$err")) {
            throw new SplitFailedException("Error calculating splits: " + data);
        } else if (!data.get("ok").equals(1.0)) {
            throw new SplitFailedException("Unable to calculate input splits: " + data.get("errmsg"));
        }

        // Comes in a format where "min" and "max" are implicit
        // and each entry is just a boundary key; not ranged
        BasicDBList splitData = (BasicDBList) data.get("splitKeys");

        if (splitData.size() == 0) {
            LOG.warn("WARNING: No Input Splits were calculated by the split code. Proceeding with a *single* split. Data may be too"
                     + " small, try lowering 'mongo.input.split_size' if this is undesirable.");
        }

        BasicDBObject lastKey = null; // Lower boundary of the first min split

        for (Object aSplitData : splitData) {
            BasicDBObject currentKey = (BasicDBObject) aSplitData;
            MongoInputSplit split = createSplitFromBounds(lastKey, currentKey);
            returnVal.add(split);
            lastKey = currentKey;
        }

        // Last max split, with empty upper boundary
        MongoInputSplit lastSplit = createSplitFromBounds(lastKey, null);
        returnVal.add(lastSplit);

        return returnVal;
    }

}
