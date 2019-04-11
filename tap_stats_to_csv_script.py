import csv
import pickle
import zlib
import glob
import sys
# This python script converts all our tap data for the locale into a csv file
rows = []
var1 = sys.argv[1]
print var1
filename = var1 + "_translations.csv"
gloname = "fms=*"
print gloname
print glob.glob(gloname)

for file in glob.glob(gloname):
    print file
    with open(file, "r") as f:
        data = pickle.loads(zlib.decompress(f.read()))
        print data
        date = file.split("_")[1]
    for kind in ["article", "scratchpad", "video", "topic",
                 "exercise", "platform"]:
        for node in data["nodes"]["%ss" % kind if kind != "platform" else "platform"].values():
            rows.append([
                date, var1, kind, node['slug'],
                node.get('metadata_translatable_word_count', 0)
                + node.get('translatable_word_count', 0),
                node.get('metadata_word_count', 0)
                + node.get('word_count', 0),
                node.get('metadata_translated_word_count', 0)
                + node.get('translated_word_count', 0),
                node.get('metadata_approved_word_count', 0)
                + node.get('approved_word_count', 0)])
with open(filename, "w") as f:
    writer = csv.writer(f)
    for row in rows:
        writer.writerow(row)
