import csv
import pickle
import zlib
import glob
import sys

rows = []
var1 = sys.argv[1]
print var1
filename = var1 + "_translations.csv"
gloname = "fms=" + var1 + "_*"
for file in glob.glob(gloname):
    with open(file, "r") as f:
        data = pickle.loads(zlib.decompress(f.read()))
        date = file.split("_")[1]
    for kind in ["article", "scratchpad", "video", "topic", "exercise", "platform"]:
        for node in data["nodes"]["%ss" % kind if kind != "platform" else "platform"].values():
            rows.append([
                date, "pt", kind, node['slug'],
                node.get('metadata_translatable_word_count', 0) + node.get('translatable_word_count', 0),
                node.get('metadata_word_count', 0) + node.get('word_count', 0),
                node.get('metadata_translated_word_count', 0) + node.get('translated_word_count', 0),
                node.get('metadata_approved_word_count', 0) + node.get('approved_word_count', 0)])
with open(filename, "w") as f:
    writer = csv.writer(f)
    for row in rows:
        writer.writerow(row)