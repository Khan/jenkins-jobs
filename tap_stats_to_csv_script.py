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
glob_name = "fms=*"

for file in glob.glob(glob_name):
    print file
    with open(file, "r") as f:
        data = pickle.loads(zlib.decompress(f.read()))
        date = file.split("_")[1]
    for kind in ["article", "scratchpad", "video", "topic",
                 "exercise", "platform"]:
        section = "%ss" % kind if kind != "platform" else "platform"
        for node in data["nodes"][section].values():
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
