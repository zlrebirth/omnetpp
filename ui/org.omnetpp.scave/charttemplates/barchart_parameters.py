from omnetpp.scave import results, chart, utils, plot
import pandas as pd

params = chart.get_properties()

# This expression selects the results (you might be able to logically simplify it)

filter_expression = params["filter"]

# TODO: maybe try to do something clever with numbers with units, instead of discarding them because of as_numeric?
# The data is returned as a Pandas DataFrame
df = results.get_parameters(filter_expression, include_attrs=True, include_itervars=True, as_numeric=True)

# You can perform any transformations on the data here

print(df)

title, legend = utils.extract_label_columns(df)

for i, c in legend:
    df[c] = pd.to_numeric(df[c], errors="ignore")

df.sort_values(by=[l for i, l in legend], axis='index', inplace=True)

plot.set_property("Graph.Title", utils.make_chart_title(df, title, legend))

if len(legend) == 2:
    df = pd.pivot_table(df, index=legend[0][1], columns=legend[1][1], values='value')
else:
    df = pd.pivot_table(df, index=[l for i, l in legend], values='value')

print(df)

# Finally, the results are plotted
plot.plot_scalars(df)

plot.set_properties(chart.get_properties())
